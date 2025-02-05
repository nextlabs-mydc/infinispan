package org.infinispan.persistence.sifs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.infinispan.commons.io.UnsignedNumeric;
import org.infinispan.commons.reactive.RxJavaInterop;
import org.infinispan.commons.time.TimeService;
import org.infinispan.commons.util.IntSet;
import org.infinispan.commons.util.IntSets;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.executors.LimitedExecutor;
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;
import org.infinispan.util.concurrent.NonBlockingManager;
import org.infinispan.util.logging.LogFactory;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.UnicastProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import net.jcip.annotations.GuardedBy;

/**
 * Keeps the entry positions persisted in a file. It consists of couple of segments, each for one modulo-range of key's
 * hashcodes (according to DataContainer's key equivalence configuration) - writes to each index segment are performed
 * by single thread, having multiple segments spreads the load between them.
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
class Index {
   private static final Log log = LogFactory.getLog(Index.class, Log.class);
   // PRE ISPN 14.0.22 GRACEFULLY VALUE = 0x512ACEF1;
   private static final int GRACEFULLY = 0x512ACEF2;
   private static final int DIRTY = 0xD112770C;
   // 4 bytes for graceful shutdown
   // 4 bytes for segment max (this way the index can be regenerated if number of segments change
   // 8 bytes root offset
   // 2 bytes root occupied
   // 8 bytes free block offset
   // 8 bytes number of elements
   private static final int INDEX_FILE_HEADER_SIZE = 34;

   private final NonBlockingManager nonBlockingManager;
   private final FileProvider dataFileProvider;
   private final FileProvider indexFileProvider;
   private final Path indexDir;
   private final Compactor compactor;
   private final int minNodeSize;
   private final int maxNodeSize;
   private final ReadWriteLock lock = new ReentrantReadWriteLock();
   @GuardedBy("lock")
   private final Segment[] segments;
   @GuardedBy("lock")
   private final FlowableProcessor<IndexRequest>[] flowableProcessors;
   private final TimeService timeService;
   private final File indexSizeFile;
   public final AtomicLongArray sizePerSegment;

   private final TemporaryTable temporaryTable;

   private final Executor executor;

   private final Throwable END_EARLY = new Throwable("SIFS Index stopping");
   // This is used to signal that a segment is not currently being used
   private final Segment emptySegment;

   private final IndexNode.OverwriteHook movedHook = new IndexNode.OverwriteHook() {
      @Override
      public boolean check(IndexRequest request, int oldFile, int oldOffset) {
         return oldFile == request.getPrevFile() && oldOffset == request.getPrevOffset();
      }

      @Override
      public void setOverwritten(IndexRequest request, int cacheSegment, boolean overwritten, int prevFile, int prevOffset) {
         if (overwritten && request.getOffset() < 0 && request.getPrevOffset() >= 0) {
            sizePerSegment.decrementAndGet(cacheSegment);
         }
      }
   };

   private final IndexNode.OverwriteHook updateHook = new IndexNode.OverwriteHook() {
      @Override
      public void setOverwritten(IndexRequest request, int cacheSegment, boolean overwritten, int prevFile, int prevOffset) {
         nonBlockingManager.complete(request, overwritten);
         if (request.getOffset() >= 0 && prevOffset < 0) {
            sizePerSegment.incrementAndGet(cacheSegment);
         } else if (request.getOffset() < 0 && prevOffset >= 0) {
            sizePerSegment.decrementAndGet(cacheSegment);
         }
      }
   };

   private final IndexNode.OverwriteHook droppedHook = new IndexNode.OverwriteHook() {
      @Override
      public void setOverwritten(IndexRequest request, int cacheSegment, boolean overwritten, int prevFile, int prevOffset) {
         if (request.getPrevFile() == prevFile && request.getPrevOffset() == prevOffset) {
            sizePerSegment.decrementAndGet(cacheSegment);
         }
      }
   };

   public Index(NonBlockingManager nonBlockingManager, FileProvider dataFileProvider, Path indexDir, int cacheSegments,
                int minNodeSize, int maxNodeSize, TemporaryTable temporaryTable, Compactor compactor,
                TimeService timeService, Executor executor, int maxOpenFiles) throws IOException {
      this.nonBlockingManager = nonBlockingManager;
      this.dataFileProvider = dataFileProvider;
      this.compactor = compactor;
      this.timeService = timeService;
      this.indexDir = indexDir;
      this.minNodeSize = minNodeSize;
      this.maxNodeSize = maxNodeSize;
      this.sizePerSegment = new AtomicLongArray(cacheSegments);
      this.indexFileProvider = new FileProvider(indexDir, maxOpenFiles, "index.", Integer.MAX_VALUE);
      this.indexSizeFile = new File(indexDir.toFile(), "index-count");

      this.segments = new Segment[cacheSegments];
      this.flowableProcessors = new FlowableProcessor[cacheSegments];

      this.temporaryTable = temporaryTable;
      // Limits the amount of concurrent updates we do to the underlying indices to be based on the number of cache
      // segments. Note that this uses blocking threads so this number is still limited by that as well
      int concurrency = Math.max(cacheSegments >> 4, 1);
      this.executor = new LimitedExecutor("sifs-index", executor, concurrency);
      this.emptySegment = new Segment(this, -1, temporaryTable);
      this.emptySegment.complete(null);
   }

   private boolean checkForExistingIndexSizeFile() {
      int cacheSegments = sizePerSegment.length();
      boolean validCount = false;
      try (RandomAccessFile indexCount = new RandomAccessFile(indexSizeFile, "r")) {
         int cacheSegmentsCount = UnsignedNumeric.readUnsignedInt(indexCount);
         if (cacheSegmentsCount == cacheSegments) {
            for (int i = 0; i < sizePerSegment.length(); ++i) {
               long value = UnsignedNumeric.readUnsignedLong(indexCount);
               if (value < 0) {
                  log.tracef("Found an invalid size for a segment, assuming index is a different format");
                  return false;
               }
               sizePerSegment.set(i, value);
            }

            if (indexCount.read() != -1) {
               log.tracef("Previous index file has more bytes than desired, assuming index is a different format");
            } else {
               validCount = true;
            }
         } else {
            log.tracef("Previous index file cache segments " + cacheSegmentsCount + " doesn't match configured" +
                  " cache segments " + cacheSegments);
         }
      } catch (IOException e) {
         log.tracef("Encountered IOException %s while reading index count file, assuming index dirty", e.getMessage());
      }

      // Delete this so the file doesn't exist and will be written at stop. If the file isn't present it is considered dirty
      indexSizeFile.delete();
      return validCount;
   }

   public static byte[] toIndexKey(org.infinispan.commons.io.ByteBuffer buffer) {
      return toIndexKey(buffer.getBuf(), buffer.getOffset(), buffer.getLength());
   }

   static byte[] toIndexKey(byte[] bytes, int offset, int length) {
      if (offset == 0 && length == bytes.length) {
         return bytes;
      }
      byte[] indexKey = new byte[length];
      System.arraycopy(bytes, 0, indexKey, 0, length);

      return indexKey;
   }

   /**
    * @return True if the index was loaded from well persisted state
    */
   public boolean load() {
      if (!checkForExistingIndexSizeFile()) {
         return false;
      }
      try {
         File statsFile = new File(indexDir.toFile(), "index.stats");
         if (!statsFile.exists()) {
            return false;
         }
         try (FileChannel statsChannel = new RandomAccessFile(statsFile, "rw").getChannel()) {

            // id / length / free / expirationTime
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + 8);
            while (read(statsChannel, buffer)) {
               buffer.flip();
               int id = buffer.getInt();
               int length = buffer.getInt();
               int free = buffer.getInt();
               long expirationTime = buffer.getLong();
               if (!compactor.addFreeFile(id, length, free, expirationTime, false)) {
                  log.tracef("Unable to add free file: %s ", id);
                  return false;
               }
               log.tracef("Loading file info for file: %s with total: %s, free: %s", id, length, free);
               buffer.flip();
            }
         }

         // No reason to keep around after loading
         statsFile.delete();

         for (Segment segment : segments) {
            if (!segment.load()) return false;
         }
         return true;
      } catch (IOException e) {
         log.trace("Exception encountered while attempting to load index, assuming index is bad", e);
         return false;
      }
   }

   public void reset() throws IOException {
      for (Segment segment : segments) {
         segment.reset();
      }
   }

   /**
    * Get record or null if expired
    */
   public EntryRecord getRecord(Object key, int cacheSegment, org.infinispan.commons.io.ByteBuffer serializedKey) throws IOException {
      return getRecord(key, cacheSegment, toIndexKey(serializedKey), IndexNode.ReadOperation.GET_RECORD);
   }

   /**
    * Get record (even if expired) or null if not present
    */
   public EntryRecord getRecordEvenIfExpired(Object key, int cacheSegment, byte[] serializedKey) throws IOException {
      return getRecord(key, cacheSegment, serializedKey, IndexNode.ReadOperation.GET_EXPIRED_RECORD);
   }

   private EntryRecord getRecord(Object key, int cacheSegment, byte[] indexKey, IndexNode.ReadOperation readOperation) throws IOException {
      lock.readLock().lock();
      try {
         return IndexNode.applyOnLeaf(segments[cacheSegment], cacheSegment, indexKey, segments[cacheSegment].rootReadLock(), readOperation);
      } finally {
         lock.readLock().unlock();
      }
   }

   /**
    * Get position or null if expired
    */
   public EntryPosition getPosition(Object key, int cacheSegment, org.infinispan.commons.io.ByteBuffer serializedKey) throws IOException {
      lock.readLock().lock();
      try {
         return IndexNode.applyOnLeaf(segments[cacheSegment], cacheSegment, toIndexKey(serializedKey), segments[cacheSegment].rootReadLock(), IndexNode.ReadOperation.GET_POSITION);
      } finally {
         lock.readLock().unlock();
      }
   }

   /**
    * Get position + numRecords, without expiration
    */
   public EntryInfo getInfo(Object key, int cacheSegment, byte[] serializedKey) throws IOException {
      lock.readLock().lock();
      try {
         return IndexNode.applyOnLeaf(segments[cacheSegment], cacheSegment, serializedKey, segments[cacheSegment].rootReadLock(), IndexNode.ReadOperation.GET_INFO);
      } finally {
         lock.readLock().unlock();
      }
   }

   public CompletionStage<Void> clear() {
      log.tracef("Clearing index");
      lock.writeLock().lock();
      try {
         AggregateCompletionStage<Void> stage = CompletionStages.aggregateCompletionStage();
         for (FlowableProcessor<IndexRequest> processor : flowableProcessors) {
            // Ignore already completed as we used this to signal that we don't own that segment anymore
            if (processor == RxJavaInterop.<IndexRequest>completedFlowableProcessor()) {
               continue;
            }
            IndexRequest clearRequest = IndexRequest.clearRequest();
            processor.onNext(clearRequest);
            stage.dependsOn(clearRequest);
         }
         for (int i = 0; i < sizePerSegment.length(); ++i) {
            sizePerSegment.set(i, 0);
         }
         return stage.freeze();
      } finally {
         lock.writeLock().unlock();
      }
   }

   public CompletionStage<Object> handleRequest(IndexRequest indexRequest) {
      flowableProcessors[indexRequest.getSegment()].onNext(indexRequest);
      return indexRequest;
   }

   public void ensureRunOnLast(Runnable runnable) {
      AtomicInteger count = new AtomicInteger(flowableProcessors.length);
      IndexRequest request = IndexRequest.syncRequest(() -> {
         if (count.decrementAndGet() == 0) {
            runnable.run();
         }
      });
      for (FlowableProcessor<IndexRequest> flowableProcessor : flowableProcessors) {
         flowableProcessor.onNext(request);
      }
   }

   public void deleteFileAsync(int fileId) {
      ensureRunOnLast(() -> {
         // After all indexes have ensured they have processed all requests - the last one will delete the file
         // This guarantees that the index can't see an outdated value
         dataFileProvider.deleteFile(fileId);
         compactor.releaseStats(fileId);
      });
   }

   public CompletionStage<Void> stop() throws InterruptedException {
      for (FlowableProcessor<IndexRequest> flowableProcessor : flowableProcessors) {
         flowableProcessor.onComplete();
      }

      AggregateCompletionStage<Void> aggregateCompletionStage = CompletionStages.aggregateCompletionStage();
      for (Segment segment : segments) {
         aggregateCompletionStage.dependsOn(segment);
      }

      // After all SIFS segments are complete we write the size
      return aggregateCompletionStage.freeze().thenRun(() -> {
         indexFileProvider.stop();
         try {
            // Create the file first as it should not be present as we deleted during startup
            indexSizeFile.createNewFile();
            try (FileOutputStream indexCountStream = new FileOutputStream(indexSizeFile)) {
               UnsignedNumeric.writeUnsignedInt(indexCountStream, this.sizePerSegment.length());
               for (int i = 0; i < sizePerSegment.length(); ++i) {
                  UnsignedNumeric.writeUnsignedLong(indexCountStream, sizePerSegment.get(i));
               }
            }

            ConcurrentMap<Integer, Compactor.Stats> map = compactor.getFileStats();
            File statsFile = new File(indexDir.toFile(), "index.stats");

            try (FileChannel statsChannel = new RandomAccessFile(statsFile, "rw").getChannel()) {
               statsChannel.truncate(0);
               // Maximum size that all ints and long can add up to
               ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + 8);
               for (Map.Entry<Integer, Compactor.Stats> entry : map.entrySet()) {
                  int file = entry.getKey();
                  int total = entry.getValue().getTotal();
                  if (total == -1) {
                     total = (int) dataFileProvider.getFileSize(file);
                  }
                  int free = entry.getValue().getFree();
                  buffer.putInt(file);
                  buffer.putInt(total);
                  buffer.putInt(free);
                  buffer.putLong(entry.getValue().getNextExpirationTime());
                  buffer.flip();
                  write(statsChannel, buffer);
                  buffer.flip();
               }
            }
         } catch (IOException e) {
            throw CompletableFutures.asCompletionException(e);
         }
      });
   }

   public long approximateSize(IntSet cacheSegments) {
      long size = 0;
      for (PrimitiveIterator.OfInt segIter = cacheSegments.iterator(); segIter.hasNext(); ) {
         int cacheSegment = segIter.nextInt();
         size += sizePerSegment.get(cacheSegment);
         if (size < 0) {
            return Long.MAX_VALUE;
         }
      }

      return size;
   }

   public long getMaxSeqId() throws IOException {
      long maxSeqId = 0;
      lock.readLock().lock();
      try {
         for (Segment seg : segments) {
            maxSeqId = Math.max(maxSeqId, IndexNode.calculateMaxSeqId(seg, seg.rootReadLock()));
         }
      } finally {
         lock.readLock().unlock();
      }
      return maxSeqId;
   }

   public void start() {
      addSegments(IntSets.immutableRangeSet(segments.length));
   }

   static boolean read(FileProvider.Handle handle, ByteBuffer buffer, long offset) throws IOException {
      assert buffer.hasRemaining();
      int read = 0;
      do {
         int newRead = handle.read(buffer, offset + read);
         if (newRead < 0) {
            return false;
         }
         read += newRead;
      } while (buffer.hasRemaining());
      return true;
   }

   static boolean read(FileChannel channel, ByteBuffer buffer) throws IOException {
      assert buffer.hasRemaining();
      do {
         int read = channel.read(buffer);
         if (read < 0) {
            return false;
         }
      } while (buffer.position() < buffer.limit());
      return true;
   }

   private static long write(FileProvider.Handle handle, ByteBuffer buffer, long offset) throws IOException {
      assert buffer.hasRemaining();
      long write = 0;
      while (buffer.hasRemaining()) {
         write += handle.write(buffer, offset + write);
      }
      return write;
   }

   private static void write(FileChannel indexFile, ByteBuffer buffer) throws IOException {
      assert buffer.hasRemaining();
      do {
         int written = indexFile.write(buffer);
         if (written < 0) {
            throw new IllegalStateException("Cannot write to index file!");
         }
      } while (buffer.position() < buffer.limit());
   }

   public CompletionStage<Void> addSegments(IntSet addedSegments) {
      // Since actualAddSegments doesn't block we try a quick write lock acquisition to possibly avoid context change
      if (lock.writeLock().tryLock()) {
         try {
            actualAddSegments(addedSegments);
         } finally {
            lock.writeLock().unlock();
         }
         return CompletableFutures.completedNull();
      }
      return CompletableFuture.runAsync(() -> {
         lock.writeLock().lock();
         try {
            actualAddSegments(addedSegments);
         } finally {
            lock.writeLock().unlock();
         }
      }, executor);
   }

   private void actualAddSegments(IntSet addedSegments) {
      for (PrimitiveIterator.OfInt segmentIter = addedSegments.iterator(); segmentIter.hasNext(); ) {
         int i = segmentIter.nextInt();

         // Segment is already running, don't do anything
         if (segments[i] != null && segments[i] != emptySegment) {
            continue;
         }

         UnicastProcessor<IndexRequest> flowableProcessor = UnicastProcessor.create(false);
         Segment segment = new Segment(this, i, temporaryTable);

         // Note we do not load the segments here as we only have to load them on startup and not if segments are
         // added at runtime
         this.segments[i] = segment;
         // It is possible to write from multiple threads
         this.flowableProcessors[i] = flowableProcessor.toSerialized();

         flowableProcessors[i]
               .observeOn(Schedulers.from(executor))
               .subscribe(segment, t -> {
                  if (t != END_EARLY)
                     log.error("Error encountered with index, SIFS may not operate properly.", t);
                  segment.completeExceptionally(t);
               }, segment);
      }
   }
   public CompletionStage<Void> removeSegments(IntSet addedSegments) {
      return CompletableFuture.supplyAsync(() -> {
         int addedCount = addedSegments.size();
         List<Segment> removedSegments = new ArrayList<>(addedCount);
         List<FlowableProcessor<IndexRequest>> removedFlowables = new ArrayList<>(addedCount);
         // We hold the lock as short as possible just to swap the segments and processors to empty ones
         lock.writeLock().lock();
         try {
            for (PrimitiveIterator.OfInt iter = addedSegments.iterator(); iter.hasNext(); ) {
               int i = iter.nextInt();
               removedSegments.add(segments[i]);
               segments[i] = emptySegment;
               removedFlowables.add(flowableProcessors[i]);
               flowableProcessors[i] = RxJavaInterop.completedFlowableProcessor();
            }
         } finally {
            lock.writeLock().unlock();
         }
         // We would love to do this outside of this stage asynchronously but unfortunately we can't say we have
         // removed the segments until the segment file is deleted
         AggregateCompletionStage<Void> stage = CompletionStages.aggregateCompletionStage();
         // We need to iterate through the entire indexes to update free space for compactor
         // Then we need to delete the segment
         for (int i = 0; i < addedCount; i++) {
            removedFlowables.get(i).onComplete();
            Segment segment = removedSegments.get(i);
            stage.dependsOn(segment
                  .thenRun(segment::delete));
         }
         return stage.freeze();
      }, executor).thenCompose(Function.identity());
   }

   static class Segment extends CompletableFuture<Void> implements Consumer<IndexRequest>, Action {
      final Index index;
      private final TemporaryTable temporaryTable;
      private final TreeMap<Short, List<IndexSpace>> freeBlocks = new TreeMap<>();
      private final ReadWriteLock rootLock = new ReentrantReadWriteLock();
      private final int id;
      private long indexFileSize = INDEX_FILE_HEADER_SIZE;

      private volatile IndexNode root;

      private Segment(Index index, int id, TemporaryTable temporaryTable) {
         this.index = index;
         this.temporaryTable = temporaryTable;

         this.id = id;

         // Just to init to empty
         root = IndexNode.emptyWithLeaves(this);
      }

      public int getId() {
         return id;
      }

      boolean load() throws IOException {
         int segmentMax = temporaryTable.getSegmentMax();
         FileProvider.Handle handle = index.indexFileProvider.getFile(id);
         try (handle) {
            ByteBuffer buffer = ByteBuffer.allocate(INDEX_FILE_HEADER_SIZE);
            boolean loaded;
            if (handle.getFileSize() >= INDEX_FILE_HEADER_SIZE && read(handle, buffer, 0)
                  && buffer.getInt(0) == GRACEFULLY && buffer.getInt(4) == segmentMax) {
               long rootOffset = buffer.getLong(8);
               short rootOccupied = buffer.getShort(16);
               long freeBlocksOffset = buffer.getLong(18);
               root = new IndexNode(this, rootOffset, rootOccupied);
               loadFreeBlocks(freeBlocksOffset);
               indexFileSize = freeBlocksOffset;
               loaded = true;
            } else {
               handle.truncate(0);
               root = IndexNode.emptyWithLeaves(this);
               loaded = false;
               // reserve space for shutdown
               indexFileSize = INDEX_FILE_HEADER_SIZE;
            }
            buffer.putInt(0, DIRTY);
            buffer.position(0);
            buffer.limit(4);
            write(handle, buffer, 0);

            return loaded;
         }
      }

      void delete() {
         // Empty segment is negative, so there is no file
         if (id >= 0) {
            index.indexFileProvider.deleteFile(id);
         }
      }

      void reset() throws IOException {
         try (FileProvider.Handle handle = index.indexFileProvider.getFile(id)) {
            handle.truncate(0);
            root = IndexNode.emptyWithLeaves(this);
            // reserve space for shutdown
            indexFileSize = INDEX_FILE_HEADER_SIZE;
            ByteBuffer buffer = ByteBuffer.allocate(INDEX_FILE_HEADER_SIZE);
            buffer.putInt(0, DIRTY);
            buffer.position(0);
            buffer.limit(4);

            write(handle, buffer, 0);
         }
      }

      @Override
      public void accept(IndexRequest request) throws Throwable {
         if (log.isTraceEnabled()) log.tracef("Indexing %s", request);
         IndexNode.OverwriteHook overwriteHook;
         IndexNode.RecordChange recordChange;
         switch (request.getType()) {
            case CLEAR:
               root = IndexNode.emptyWithLeaves(this);
               try (FileProvider.Handle handle = index.indexFileProvider.getFile(id)) {
                  handle.truncate(0);
               }
               indexFileSize = INDEX_FILE_HEADER_SIZE;
               freeBlocks.clear();
               index.nonBlockingManager.complete(request, null);
               return;
            case SYNC_REQUEST:
               Runnable runnable = (Runnable) request.getKey();
               runnable.run();
               index.nonBlockingManager.complete(request, null);
               return;
            case MOVED:
               recordChange = IndexNode.RecordChange.MOVE;
               overwriteHook = index.movedHook;
               break;
            case UPDATE:
               recordChange = IndexNode.RecordChange.INCREASE;
               overwriteHook = index.updateHook;
               break;
            case DROPPED:
               recordChange = IndexNode.RecordChange.DECREASE;
               overwriteHook = index.droppedHook;
               break;
            case FOUND_OLD:
               recordChange = IndexNode.RecordChange.INCREASE_FOR_OLD;
               overwriteHook = IndexNode.NOOP_HOOK;
               break;
            default:
               throw new IllegalArgumentException(request.toString());
         }
         try {
            IndexNode.setPosition(root, request, overwriteHook, recordChange);
         } catch (IllegalStateException e) {
            request.completeExceptionally(e);
         }
         temporaryTable.removeConditionally(request.getSegment(), request.getKey(), request.getFile(), request.getOffset());
         if (request.getType() != IndexRequest.Type.UPDATE) {
            // The update type will complete it in the switch statement above
            index.nonBlockingManager.complete(request, null);
         }
      }

      // This is ran when the flowable ends either via normal termination or error
      @Override
      public void run() throws IOException {
         try {
            IndexSpace rootSpace = allocateIndexSpace(root.length());
            root.store(rootSpace);
            try (FileProvider.Handle handle = index.indexFileProvider.getFile(id)) {
               ByteBuffer buffer = ByteBuffer.allocate(4);
               buffer.putInt(0, freeBlocks.size());
               long offset = indexFileSize;
               offset += write(handle, buffer, offset);
               for (Map.Entry<Short, List<IndexSpace>> entry : freeBlocks.entrySet()) {
                  List<IndexSpace> list = entry.getValue();
                  int requiredSize = 8 + list.size() * 10;
                  buffer = buffer.capacity() < requiredSize ? ByteBuffer.allocate(requiredSize) : buffer;
                  buffer.position(0);
                  buffer.limit(requiredSize);
                  // TODO: change this to short
                  buffer.putInt(entry.getKey());
                  buffer.putInt(list.size());
                  for (IndexSpace space : list) {
                     buffer.putLong(space.offset);
                     buffer.putShort(space.length);
                  }
                  buffer.flip();
                  offset += write(handle, buffer, offset);
               }
               int headerWithoutMagic = INDEX_FILE_HEADER_SIZE - 4;
               buffer = buffer.capacity() < headerWithoutMagic ? ByteBuffer.allocate(headerWithoutMagic) : buffer;
               buffer.position(0);
               // we need to set limit ahead, otherwise the putLong could throw IndexOutOfBoundsException
               buffer.limit(headerWithoutMagic);
               buffer.putInt(index.segments.length);
               buffer.putLong(rootSpace.offset);
               buffer.putShort(rootSpace.length);
               buffer.putLong(indexFileSize);
               buffer.flip();
               write(handle, buffer, 4);

               buffer.position(0);
               buffer.limit(4);
               buffer.putInt(0, GRACEFULLY);
               write(handle, buffer, 0);
            }

            complete(null);
         } catch (Throwable t) {
            completeExceptionally(t);
         }
      }

      private void loadFreeBlocks(long freeBlocksOffset) throws IOException {
         ByteBuffer buffer = ByteBuffer.allocate(8);
         buffer.limit(4);
         long offset = freeBlocksOffset;
         try (FileProvider.Handle handle = index.indexFileProvider.getFile(id)) {
            if (!read(handle, buffer, offset)) {
               throw new IOException("Cannot read free blocks lists!");
            }
            offset += 4;
            int numLists = buffer.getInt(0);
            for (int i = 0; i < numLists; ++i) {
               buffer.position(0);
               buffer.limit(8);
               if (!read(handle, buffer, offset)) {
                  throw new IOException("Cannot read free blocks lists!");
               }
               offset += 8;
               // TODO: change this to short
               int blockLength = buffer.getInt(0);
               assert blockLength <= Short.MAX_VALUE;
               int listSize = buffer.getInt(4);
               // Ignore any free block that had no entries as it adds time complexity to our lookup
               if (listSize > 0) {
                  int requiredSize = 10 * listSize;
                  buffer = buffer.capacity() < requiredSize ? ByteBuffer.allocate(requiredSize) : buffer;
                  buffer.position(0);
                  buffer.limit(requiredSize);
                  if (!read(handle, buffer, offset)) {
                     throw new IOException("Cannot read free blocks lists!");
                  }
                  offset += requiredSize;
                  buffer.flip();
                  ArrayList<IndexSpace> list = new ArrayList<>(listSize);
                  for (int j = 0; j < listSize; ++j) {
                     list.add(new IndexSpace(buffer.getLong(), buffer.getShort()));
                  }
                  freeBlocks.put((short) blockLength, list);
               }
            }
         }
      }

      public FileProvider.Handle getIndexFile() throws IOException {
         return index.indexFileProvider.getFile(id);
      }

      public void forceIndexIfOpen(boolean metaData) throws IOException {
         FileProvider.Handle handle = index.indexFileProvider.getFileIfOpen(id);
         if (handle != null) {
            try (handle) {
               handle.force(metaData);
            }
         }
      }

      public FileProvider getFileProvider() {
         return index.dataFileProvider;
      }

      public Compactor getCompactor() {
         return index.compactor;
      }

      public IndexNode getRoot() {
         // this has to be called with rootLock locked!
         return root;
      }

      public void setRoot(IndexNode root) {
         rootLock.writeLock().lock();
         this.root = root;
         rootLock.writeLock().unlock();
      }

      public int getMaxNodeSize() {
         return index.maxNodeSize;
      }

      public int getMinNodeSize() {
         return index.minNodeSize;
      }

      // this should be accessed only from the updater thread
      IndexSpace allocateIndexSpace(short length) {
         // Use tailMap so that we only require O(logN) to find the iterator
         // This avoids an additional O(logN) to do an entry removal
         Iterator<Map.Entry<Short, List<IndexSpace>>> iter = freeBlocks.tailMap(length).entrySet().iterator();
         while (iter.hasNext()) {
            Map.Entry<Short, List<IndexSpace>> entry = iter.next();
            short spaceLength = entry.getKey();
            // Only use the space if it is only 25% larger to avoid too much fragmentation
            if ((length + (length >> 2)) < spaceLength) {
               break;
            }
            List<IndexSpace> list = entry.getValue();
            if (!list.isEmpty()) {
               IndexSpace spaceToReturn = list.remove(list.size() - 1);
               if (list.isEmpty()) {
                  iter.remove();
               }
               return spaceToReturn;
            }
            iter.remove();
         }
         long oldSize = indexFileSize;
         indexFileSize += length;
         return new IndexSpace(oldSize, length);
      }

      // this should be accessed only from the updater thread
      void freeIndexSpace(long offset, short length) {
         if (length <= 0) throw new IllegalArgumentException("Offset=" + offset + ", length=" + length);
         // TODO: fragmentation!
         // TODO: memory bounds!
         if (offset + length < indexFileSize) {
            freeBlocks.computeIfAbsent(length, k -> new ArrayList<>()).add(new IndexSpace(offset, length));
         } else {
            indexFileSize -= length;
            try (FileProvider.Handle handle = index.indexFileProvider.getFile(id)) {
               handle.truncate(indexFileSize);
            } catch (IOException e) {
               log.cannotTruncateIndex(e);
            }
         }
      }

      Lock rootReadLock() {
         return rootLock.readLock();
      }

      public TimeService getTimeService() {
         return index.timeService;
      }
   }

   /**
    * Offset-length pair
    */
   static class IndexSpace {
      protected long offset;
      protected short length;

      IndexSpace(long offset, short length) {
         this.offset = offset;
         this.length = length;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof IndexSpace)) return false;

         IndexSpace innerNode = (IndexSpace) o;

         return length == innerNode.length && offset == innerNode.offset;
      }

      @Override
      public int hashCode() {
         int result = (int) (offset ^ (offset >>> 32));
         result = 31 * result + length;
         return result;
      }

      @Override
      public String toString() {
         return String.format("[%d-%d(%d)]", offset, offset + length, length);
      }
   }

   <V> Flowable<EntryRecord> publish(IntSet cacheSegments, boolean loadValues) {
      return Flowable.fromIterable(cacheSegments)
            .concatMap(segment -> segments[segment].root.publish(segment, loadValues));
   }
}
