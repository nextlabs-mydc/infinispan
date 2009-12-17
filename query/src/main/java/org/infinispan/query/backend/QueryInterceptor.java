package org.infinispan.query.backend;

import org.hibernate.search.backend.TransactionContext;
import org.hibernate.search.backend.Work;
import org.hibernate.search.backend.WorkType;
import org.hibernate.search.engine.SearchFactoryImplementor;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.marshall.MarshalledValue;

import javax.transaction.TransactionManager;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import static org.infinispan.query.backend.KeyTransformationHandler.keyToString;

/**
 * This interceptor will be created when the System Property "infinispan.query.indexLocalOnly" is "false"
 * <p/>
 * This type of interceptor will allow the indexing of data even when it comes from other caches within a cluster.
 * <p/>
 * However, if the a cache would not be putting the data locally, the interceptor will not index it.
 *
 * @author Navin Surtani
 * @since 4.0
 */

public class QueryInterceptor extends CommandInterceptor {

   protected SearchFactoryImplementor searchFactory;
   protected TransactionManager transactionManager;

   @Inject
   public void init(SearchFactoryImplementor searchFactory, TransactionManager transactionManager) {
      this.searchFactory = searchFactory;
      this.transactionManager = transactionManager;
   }

   protected boolean shouldModifyIndexes(InvocationContext ctx) {
      return true;
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {

      // This method will get the put() calls on the cache and then send them into Lucene once it's successful.
      // do the actual put first.
      Object toReturn = invokeNextInterceptor(ctx, command);

      if (shouldModifyIndexes(ctx))
         addToIndexes(extractValue(command.getValue()), extractValue(command.getKey()));

      return toReturn;
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      // remove the object out of the cache first.
      Object valueRemoved = invokeNextInterceptor(ctx, command);

      if (command.isSuccessful() && shouldModifyIndexes(ctx))
         removeFromIndexes(extractValue(valueRemoved), extractValue(command.getKey()));

      return valueRemoved;
   }


   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      Object valueReplaced = invokeNextInterceptor(ctx, command);
      if (valueReplaced != null && shouldModifyIndexes(ctx)) {

         Object[] parameters = command.getParameters();
         Object key = extractValue(command.getKey());

         removeFromIndexes(extractValue(parameters[1]), key);
         addToIndexes(extractValue(parameters[2]), key);
      }

      return valueReplaced;
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      Object mapPut = invokeNextInterceptor(ctx, command);

      if (shouldModifyIndexes(ctx)) {
         Map<Object, Object> dataMap = command.getMap();

         // Loop through all the keys and put those key, value pairings into lucene.

         for (Map.Entry entry : dataMap.entrySet()) {
            addToIndexes(extractValue(entry.getValue()), extractValue(entry.getKey()));
         }
      }
      return mapPut;
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      Object returnValue = invokeNextInterceptor(ctx, command);

      if (shouldModifyIndexes(ctx)) {
         if (trace) log.trace("shouldModifyIndexes() is true and we can clear the indexes");

         Set<Class<?>> classes = searchFactory.getDocumentBuildersIndexedEntities().keySet();
         for (Class c : classes) {
            Serializable id = null;
            if (log.isDebugEnabled()) log.debug("Clearing indexes for class: - " + c);
            searchFactory.getWorker().performWork(new Work<Object>(c, id, WorkType.PURGE_ALL),
                                                  new TransactionalEventTransactionContext(transactionManager));
         }
      }
      return returnValue;
   }


   // Method that will be called when data needs to be added into Lucene.
   protected void addToIndexes(Object value, Object key) {
      if (trace) log.trace("Adding to indexes for key [{0}] and value [{0}]", key, value);

      // The key here is the String representation of the key that is stored in the cache.
      // The key is going to be the documentID for Lucene.
      // The object parameter is the actual value that needs to be put into lucene.
      if (value == null) throw new NullPointerException("Cannot handle a null value!");
      TransactionContext transactionContext = new TransactionalEventTransactionContext(transactionManager);
      searchFactory.getWorker().performWork(new Work<Object>(value, keyToString(key), WorkType.ADD), transactionContext);
   }


   // Method that will be called when data needs to be removed from Lucene.
   protected void removeFromIndexes(Object value, Object key) {

      // The key here is the String representation of the key that is stored in the cache.
      // The key is going to be the documentID for Lucene.
      // The object parameter is the actual value that needs to be removed from lucene.
      if (value == null) throw new NullPointerException("Cannot handle a null value!");
      TransactionContext transactionContext = new TransactionalEventTransactionContext(transactionManager);
      searchFactory.getWorker().performWork(new Work<Object>(value, keyToString(key), WorkType.DELETE), transactionContext);
   }

   private Object extractValue(Object wrappedValue) {
      if (wrappedValue instanceof MarshalledValue)
         return ((MarshalledValue) wrappedValue).get();
      else
         return wrappedValue;
   }
}
