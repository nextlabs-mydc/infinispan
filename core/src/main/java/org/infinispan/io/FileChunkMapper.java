/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.io;

import org.infinispan.Cache;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;

/**
 *
 */
class FileChunkMapper {

   private static final Log log = LogFactory.getLog(FileChunkMapper.class);

   private final GridFile file;
   private final String name;
   private final Cache<String, byte[]> cache;

   public FileChunkMapper(GridFile file, Cache<String, byte[]> cache) {
      this.file = file;
      this.name = file.getPath();
      this.cache = cache;
   }

   public int getChunkSize() {
      return file.getChunkSize();
   }

   public byte[] fetchChunk(int chunkNumber) {
      String key = getChunkKey(chunkNumber);
      byte[] val = cache.get(key);
      if (log.isTraceEnabled())
         log.trace("fetching key=" + key + ": " + (val != null ? val.length + " bytes" : "null"));
      return val;
   }

   public void storeChunk(int chunkNumber, byte[] buffer, int length) {
      String key = getChunkKey(chunkNumber);
      byte[] val = trim(buffer, length);
      cache.put(key, val);
      if (log.isTraceEnabled())
         log.trace("put(): key=" + key + ": " + val.length + " bytes");
   }

   public void removeChunk(int chunkNumber) {
      cache.remove(getChunkKey(chunkNumber));
   }

   private byte[] trim(byte[] buffer, int length) {
      byte[] val = new byte[length];
      System.arraycopy(buffer, 0, val, 0, length);
      return val;
   }

   private String getChunkKey(int chunkNumber) {
      return name + ".#" + chunkNumber;
   }
}
