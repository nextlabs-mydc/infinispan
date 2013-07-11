package org.infinispan.loaders.jdbc.configuration;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.LoadersConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ConfigurationParser;
import org.infinispan.configuration.parsing.Namespace;
import org.infinispan.configuration.parsing.Namespaces;
import org.infinispan.configuration.parsing.ParseUtils;
import org.infinispan.configuration.parsing.Parser52;
import org.infinispan.configuration.parsing.XMLExtendedStreamReader;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import static org.infinispan.commons.util.StringPropertyReplacer.replaceProperties;

/**
 *
 * JdbcCacheStoreConfigurationParser60.
 *
 * @author Galder Zamarreño
 * @since 6.0
 */
@Namespaces({
   @Namespace(uri = "urn:infinispan:config:jdbc:6.0", root = "stringKeyedJdbcStore"),
   @Namespace(root = "stringKeyedJdbcStore"),
   @Namespace(uri = "urn:infinispan:config:jdbc:6.0", root = "binaryKeyedJdbcStore"),
   @Namespace(root = "binaryKeyedJdbcStore"),
   @Namespace(uri = "urn:infinispan:config:jdbc:6.0", root = "mixedKeyedJdbcStore"),
   @Namespace(root = "mixedKeyedJdbcStore"),
})
public class JdbcCacheStoreConfigurationParser60 implements ConfigurationParser {

   public JdbcCacheStoreConfigurationParser60() {
   }

   @Override
   public void readElement(final XMLExtendedStreamReader reader, final ConfigurationBuilderHolder holder)
         throws XMLStreamException {
      ConfigurationBuilder builder = holder.getCurrentConfigurationBuilder();

      Element element = Element.forName(reader.getLocalName());
      switch (element) {
      case STRING_KEYED_JDBC_STORE: {
         parseStringKeyedJdbcStore(reader, builder.loaders());
         break;
      }
      case BINARY_KEYED_JDBC_STORE: {
         parseBinaryKeyedJdbcStore(reader, builder.loaders());
         break;
      }
      case MIXED_KEYED_JDBC_STORE: {
         parseMixedKeyedJdbcStore(reader, builder.loaders());
         break;
      }
      default: {
         throw ParseUtils.unexpectedElement(reader);
      }
      }
   }

   private void parseStringKeyedJdbcStore(final XMLExtendedStreamReader reader,
         LoadersConfigurationBuilder loadersBuilder) throws XMLStreamException {
      JdbcStringBasedCacheStoreConfigurationBuilder builder = new JdbcStringBasedCacheStoreConfigurationBuilder(
            loadersBuilder);
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case KEY_TO_STRING_MAPPER:
            builder.key2StringMapper(value);
            break;
         default:
            Parser52.parseCommonStoreAttributes(reader, i, builder);
            break;
         }
      }
      while (reader.hasNext() && (reader.nextTag() != XMLStreamConstants.END_ELEMENT)) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
         case STRING_KEYED_TABLE: {
            parseTable(reader, builder.table());
            break;
         }
         default: {
            parseCommonJdbcStoreElements(reader, element, builder);
            break;
         }
         }
      }
      loadersBuilder.addStore(builder);
   }

   private void parseBinaryKeyedJdbcStore(XMLExtendedStreamReader reader, LoadersConfigurationBuilder loadersBuilder)
         throws XMLStreamException {
      JdbcBinaryCacheStoreConfigurationBuilder builder = new JdbcBinaryCacheStoreConfigurationBuilder(
            loadersBuilder);
      parseCommonJdbcStoreAttributes(reader, builder);
      while (reader.hasNext() && (reader.nextTag() != XMLStreamConstants.END_ELEMENT)) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
         case BINARY_KEYED_TABLE: {
            parseTable(reader, builder.table());
            break;
         }
         default: {
            parseCommonJdbcStoreElements(reader, element, builder);
            break;
         }
         }
      }
      loadersBuilder.addStore(builder);
   }

   private void parseCommonJdbcStoreElements(XMLExtendedStreamReader reader, Element element, AbstractJdbcCacheStoreConfigurationBuilder<?, ?> builder) throws XMLStreamException {
      switch (element) {
      case CONNECTION_POOL: {
         parseConnectionPoolAttributes(reader, builder.connectionPool());
         break;
      }
      case DATA_SOURCE: {
         parseDataSourceAttributes(reader, builder.dataSource());
         break;
      }
      case SIMPLE_CONNECTION: {
         parseSimpleConnectionAttributes(reader, builder.simpleConnection());
         break;
      }
      default: {
         Parser52.parseCommonStoreChildren(reader, builder);
         break;
      }
      }
   }

   private void parseCommonJdbcStoreAttributes(XMLExtendedStreamReader reader, AbstractJdbcCacheStoreConfigurationBuilder<?, ?> builder) throws XMLStreamException {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         Parser52.parseCommonStoreAttributes(reader, i, builder);
      }
   }

   private void parseDataSourceAttributes(XMLExtendedStreamReader reader,
         ManagedConnectionFactoryConfigurationBuilder<?> builder) throws XMLStreamException {
      String jndiUrl = ParseUtils.requireSingleAttribute(reader, Attribute.JNDI_URL.getLocalName());
      builder.jndiUrl(jndiUrl);
      ParseUtils.requireNoContent(reader);
   }

   private void parseConnectionPoolAttributes(XMLExtendedStreamReader reader,
         PooledConnectionFactoryConfigurationBuilder<?> builder) throws XMLStreamException {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case CONNECTION_URL: {
            builder.connectionUrl(value);
            break;
         }
         case DRIVER_CLASS: {
            builder.driverClass(value);
            break;
         }
         case PASSWORD: {
            builder.password(value);
            break;
         }
         case USERNAME: {
            builder.username(value);
            break;
         }
         default: {
            throw ParseUtils.unexpectedAttribute(reader, i);
         }
         }
      }
      ParseUtils.requireNoContent(reader);
   }

   private void parseSimpleConnectionAttributes(XMLExtendedStreamReader reader,
         SimpleConnectionFactoryConfigurationBuilder<?> builder) throws XMLStreamException {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case CONNECTION_URL: {
            builder.connectionUrl(value);
            break;
         }
         case DRIVER_CLASS: {
            builder.driverClass(value);
            break;
         }
         case PASSWORD: {
            builder.password(value);
            break;
         }
         case USERNAME: {
            builder.username(value);
            break;
         }
         default: {
            throw ParseUtils.unexpectedAttribute(reader, i);
         }
         }
      }
      ParseUtils.requireNoContent(reader);
   }

   private void parseMixedKeyedJdbcStore(XMLExtendedStreamReader reader, LoadersConfigurationBuilder loadersBuilder)
         throws XMLStreamException {
      JdbcMixedCacheStoreConfigurationBuilder builder = new JdbcMixedCacheStoreConfigurationBuilder(loadersBuilder);
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case KEY_TO_STRING_MAPPER:
            builder.key2StringMapper(value);
            break;
         default:
            Parser52.parseCommonStoreAttributes(reader, i, builder);
            break;
         }
      }
      while (reader.hasNext() && (reader.nextTag() != XMLStreamConstants.END_ELEMENT)) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
         case STRING_KEYED_TABLE: {
            parseTable(reader, builder.stringTable());
            break;
         }
         case BINARY_KEYED_TABLE: {
            parseTable(reader, builder.binaryTable());
            break;
         }
         default: {
            parseCommonJdbcStoreElements(reader, element, builder);
            break;
         }
         }
      }
      loadersBuilder.addStore(builder);
   }

   private void parseTable(XMLExtendedStreamReader reader, TableManipulationConfigurationBuilder<?, ?> builder)
         throws XMLStreamException {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = replaceProperties(reader.getAttributeValue(i));
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case BATCH_SIZE: {
            builder.batchSize(Integer.parseInt(value));
            break;
         }
         case CREATE_ON_START: {
            builder.createOnStart(Boolean.parseBoolean(value));
            break;
         }
         case DROP_ON_EXIT: {
            builder.dropOnExit(Boolean.parseBoolean(value));
            break;
         }
         case FETCH_SIZE: {
            builder.fetchSize(Integer.parseInt(value));
            break;
         }
         case PREFIX: {
            builder.tableNamePrefix(value);
            break;
         }
         default: {
            throw ParseUtils.unexpectedAttribute(reader, i);
         }
         }
      }
      parseTableElements(reader, builder);
   }

   private void parseTableElements(XMLExtendedStreamReader reader, TableManipulationConfigurationBuilder<?, ?> builder)
         throws XMLStreamException {
      while (reader.hasNext() && (reader.nextTag() != XMLStreamConstants.END_ELEMENT)) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
         case ID_COLUMN: {
            Column column = parseTableElementAttributes(reader);
            builder.idColumnName(column.name);
            builder.idColumnType(column.type);
            break;
         }
         case DATA_COLUMN: {
            Column column = parseTableElementAttributes(reader);
            builder.dataColumnName(column.name);
            builder.dataColumnType(column.type);
            break;
         }
         case TIMESTAMP_COLUMN: {
            Column column = parseTableElementAttributes(reader);
            builder.timestampColumnName(column.name);
            builder.timestampColumnType(column.type);
            break;
         }
         default: {
            throw ParseUtils.unexpectedElement(reader);
         }
         }
      }
   }

   private Column parseTableElementAttributes(XMLExtendedStreamReader reader) throws XMLStreamException {
      Column column = new Column();
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
         switch (attribute) {
         case NAME: {
            column.name = value;
            break;
         }
         case TYPE: {
            column.type = value;
            break;
         }
         default: {
            throw ParseUtils.unexpectedAttribute(reader, i);
         }
         }
      }
      ParseUtils.requireNoContent(reader);
      return column;
   }

   class Column {
      String name;
      String type;
   }
}
