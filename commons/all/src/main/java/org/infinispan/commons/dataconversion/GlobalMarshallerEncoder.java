package org.infinispan.commons.dataconversion;

import org.infinispan.commons.marshall.Marshaller;

/**
 * Encoder that uses the GlobalMarshaller to encode/decode data.
 *
 * @since 9.2
 * @deprecated Since 11.0, will be removed with ISPN-9622
 */
@Deprecated(forRemoval=true)
public class GlobalMarshallerEncoder extends MarshallerEncoder {

   public GlobalMarshallerEncoder(Marshaller globalMarshaller) {
      super(globalMarshaller);
   }

   @Override
   public MediaType getStorageFormat() {
      return MediaType.APPLICATION_INFINISPAN_MARSHALLED;
   }

   @Override
   public short id() {
      return EncoderIds.GLOBAL_MARSHALLER;
   }
}
