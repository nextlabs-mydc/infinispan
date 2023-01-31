package org.infinispan.server.configuration.hotrod;

import java.util.EnumSet;

import org.infinispan.commons.configuration.io.ConfigurationReader;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ConfigurationParser;
import org.infinispan.configuration.parsing.Namespace;
import org.infinispan.configuration.parsing.Namespaces;
import org.infinispan.configuration.parsing.ParseUtils;
import org.infinispan.server.Server;
import org.infinispan.server.configuration.ServerConfigurationBuilder;
import org.infinispan.server.configuration.ServerConfigurationParser;
import org.infinispan.server.core.configuration.EncryptionConfigurationBuilder;
import org.infinispan.server.core.configuration.SaslAuthenticationConfigurationBuilder;
import org.infinispan.server.core.configuration.SaslConfigurationBuilder;
import org.infinispan.server.core.configuration.SniConfigurationBuilder;
import org.infinispan.server.hotrod.configuration.Attribute;
import org.infinispan.server.hotrod.configuration.Element;
import org.infinispan.server.hotrod.configuration.HotRodServerConfigurationBuilder;
import org.infinispan.server.security.ElytronSASLAuthenticator;
import org.kohsuke.MetaInfServices;
import org.wildfly.security.sasl.WildFlySasl;

/**
 * Server endpoint configuration parser
 *
 * @author Tristan Tarrant
 * @since 10.0
 */
@MetaInfServices
@Namespaces({
      @Namespace(root = "hotrod-connector"),
      @Namespace(uri = "urn:infinispan:server:*", root = "hotrod-connector"),
})
public class HotRodServerConfigurationParser implements ConfigurationParser {
   private static org.infinispan.util.logging.Log coreLog = org.infinispan.util.logging.LogFactory.getLog(ServerConfigurationParser.class);

   @Override
   public void readElement(ConfigurationReader reader, ConfigurationBuilderHolder holder) {
      if (!holder.inScope(ServerConfigurationParser.ENDPOINTS_SCOPE)) {
         throw coreLog.invalidScope(ServerConfigurationParser.ENDPOINTS_SCOPE, holder.getScope());
      }
      GlobalConfigurationBuilder builder = holder.getGlobalConfigurationBuilder();

      Element element = Element.forName(reader.getLocalName());
      switch (element) {
         case HOTROD_CONNECTOR: {
            ServerConfigurationBuilder serverBuilder = builder.module(ServerConfigurationBuilder.class);
            if (serverBuilder != null) {
               parseHotRodConnector(reader, serverBuilder, serverBuilder.endpoints().current().addConnector(HotRodServerConfigurationBuilder.class));
            }
            break;
         }
         default: {
            throw ParseUtils.unexpectedElement(reader);
         }
      }
   }

   @Override
   public Namespace[] getNamespaces() {
      return ParseUtils.getNamespaceAnnotations(getClass());
   }

   private void parseHotRodConnector(ConfigurationReader reader, ServerConfigurationBuilder serverBuilder, HotRodServerConfigurationBuilder builder) {
      boolean dedicatedSocketBinding = false;
      String securityRealm = null;
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case EXTERNAL_HOST: {
               builder.proxyHost(value);
               break;
            }
            case EXTERNAL_PORT: {
               builder.proxyPort(Integer.parseInt(value));
               break;
            }
            case NAME: {
               builder.name(value);
               break;
            }
            case SOCKET_BINDING: {
               builder.socketBinding(value);
               builder.startTransport(true);
               dedicatedSocketBinding = true;
               break;
            }
            case SECURITY_REALM: {
               builder.authentication().securityRealm(value);
               break;
            }
            case NETWORK_PREFIX_OVERRIDE: {
               builder.topologyNetworkPrefixOverride(Boolean.parseBoolean(value));
               break;
            }
            default: {
               ServerConfigurationParser.parseCommonConnectorAttributes(reader, i, serverBuilder, builder);
            }
         }
      }
      if (!dedicatedSocketBinding) {
         builder.socketBinding(serverBuilder.endpoints().current().singlePort().socketBinding()).startTransport(false);
      }
      while (reader.inTag()) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
            case TOPOLOGY_STATE_TRANSFER: {
               parseTopologyStateTransfer(reader, builder);
               break;
            }
            case AUTHENTICATION: {
               parseAuthentication(reader, serverBuilder, builder.authentication().enable(), securityRealm);
               break;
            }
            case ENCRYPTION: {
               if (!dedicatedSocketBinding) {
                  throw Server.log.cannotConfigureProtocolEncryptionUnderSinglePort();
               }
               parseEncryption(reader, serverBuilder, builder.encryption(), securityRealm);
               break;
            }
            default: {
               ServerConfigurationParser.parseCommonConnectorElements(reader, builder);
            }
         }
      }
   }

   private void parseEncryption(ConfigurationReader reader, ServerConfigurationBuilder serverBuilder, EncryptionConfigurationBuilder encryption, String securityRealm) {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case REQUIRE_SSL_CLIENT_AUTH: {
               encryption.requireClientAuth(Boolean.parseBoolean(value));
               break;
            }
            case SECURITY_REALM: {
               securityRealm = value;
               break;
            }
            default: {
               throw ParseUtils.unexpectedAttribute(reader, i);
            }
         }
      }
      if (securityRealm == null) {
         throw Server.log.encryptionWithoutSecurityRealm();
      } else {
         encryption.realm(securityRealm).sslContext(serverBuilder.serverSSLContextSupplier(securityRealm));
      }
      while (reader.inTag(Element.ENCRYPTION)) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
            case SNI: {
               if (reader.getAttributeCount() > 0) {
                  parseSni(reader, serverBuilder, encryption.addSni());
               }
               break;
            }
            default: {
               throw ParseUtils.unexpectedElement(reader);
            }
         }
      }
   }

   private void parseSni(ConfigurationReader reader, ServerConfigurationBuilder serverBuilder, SniConfigurationBuilder sni) {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case HOST_NAME: {
               sni.host(value);
               break;
            }
            case SECURITY_REALM: {
               sni.realm(value);
               sni.sslContext(serverBuilder.serverSSLContextSupplier(value));
               break;
            }
            default: {
               throw ParseUtils.unexpectedAttribute(reader, i);
            }
         }
      }
      ParseUtils.requireNoContent(reader);
   }

   private void parseAuthentication(ConfigurationReader reader, ServerConfigurationBuilder serverBuilder, SaslAuthenticationConfigurationBuilder builder, String securityRealm) {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case SECURITY_REALM: {
               securityRealm = value;
               break;
            }
            default: {
               throw ParseUtils.unexpectedAttribute(reader, i);
            }
         }
      }
      if (securityRealm == null) {
         securityRealm = serverBuilder.endpoints().current().securityRealm();
      }
      if (securityRealm == null) {
         throw Server.log.authenticationWithoutSecurityRealm();
      }
      // Automatically set the digest realm name. It can be overridden by the user
      builder.sasl().addMechProperty(WildFlySasl.REALM_LIST, securityRealm);
      String serverPrincipal = null;
      while (reader.inTag()) {
         Element element = Element.forName(reader.getLocalName());
         switch (element) {
            case SASL: {
               serverPrincipal = parseSasl(reader, builder);
               break;
            }
            default: {
               throw ParseUtils.unexpectedElement(reader);
            }
         }
      }
      builder.securityRealm(securityRealm);
      builder.sasl().authenticator(new ElytronSASLAuthenticator(securityRealm, serverPrincipal, builder.sasl().mechanisms()));
   }

   private String parseSasl(ConfigurationReader reader, SaslAuthenticationConfigurationBuilder builder) {
      SaslConfigurationBuilder sasl = builder.sasl();
      String serverPrincipal = null;
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case SERVER_PRINCIPAL: {
               serverPrincipal = value;
               break;
            }
            case SERVER_NAME: {
               sasl.serverName(value);
               break;
            }
            case MECHANISMS: {
               for (String mech : reader.getListAttributeValue(i)) {
                  sasl.addAllowedMech(mech);
               }
               break;
            }
            case QOP: {
               for (String qop : reader.getListAttributeValue(i)) {
                  sasl.addQOP(qop);
               }
               break;
            }
            case STRENGTH: {
               for (String s : reader.getListAttributeValue(i)) {
                  sasl.addStrength(s);
               }
               break;
            }
            case POLICY: {
               for (String p : reader.getListAttributeValue(i)) {
                  sasl.addPolicy(p);
               }
               break;
            }
            default: {
               throw ParseUtils.unexpectedAttribute(reader, i);
            }
         }
      }
      final EnumSet<Element> visited = EnumSet.noneOf(Element.class);
      while (reader.inTag()) {
         final Element element = Element.forName(reader.getLocalName());
         switch (element) {
            case POLICY: {
               if (reader.getSchema().since(13, 0) || visited.contains(element)) {
                  throw ParseUtils.unexpectedElement(reader);
               } else {
                  visited.add(element);
               }
               parsePolicy(reader, builder);
               break;
            }
            case PROPERTIES: {
               // JSON/YAML map properties to attributes
               for (int i = 0; i < reader.getAttributeCount(); i++) {
                  sasl.addProperty(reader.getAttributeName(i), reader.getAttributeValue(i));
               }
               ParseUtils.requireNoContent(reader);
               break;
            }
            case PROPERTY: {
               sasl.addProperty(ParseUtils.requireSingleAttribute(reader, Attribute.NAME), reader.getElementText());
               break;
            }
            default: {
               throw ParseUtils.unexpectedElement(reader);
            }
         }
      }
      return serverPrincipal;
   }

   void parsePolicy(ConfigurationReader reader, SaslAuthenticationConfigurationBuilder builder) {
      if (reader.getAttributeCount() > 0) {
         throw ParseUtils.unexpectedAttribute(reader, 0);
      }
      SaslConfigurationBuilder sasl = builder.sasl();
      // Handle nested elements.
      final EnumSet<Element> visited = EnumSet.noneOf(Element.class);
      while (reader.inTag()) {
         final Element element = Element.forName(reader.getLocalName());
         if (visited.contains(element)) {
            throw ParseUtils.unexpectedElement(reader);
         }
         visited.add(element);
         String value = ParseUtils.readStringAttributeElement(reader, Attribute.VALUE.toString());
         switch (element) {
            case FORWARD_SECRECY:
            case NO_ACTIVE:
            case NO_ANONYMOUS:
            case NO_DICTIONARY:
            case NO_PLAIN_TEXT:
            case PASS_CREDENTIALS: {
               if ("true".equals(value)) {
                  sasl.addPolicy(element.toString());
               }
               break;
            }
            default: {
               throw ParseUtils.unexpectedElement(reader);
            }
         }
      }
   }

   private void parseTopologyStateTransfer(ConfigurationReader reader, HotRodServerConfigurationBuilder builder) {
      for (int i = 0; i < reader.getAttributeCount(); i++) {
         ParseUtils.requireNoNamespaceAttribute(reader, i);
         String value = reader.getAttributeValue(i);
         Attribute attribute = Attribute.forName(reader.getAttributeName(i));
         switch (attribute) {
            case LOCK_TIMEOUT: {
               builder.topologyLockTimeout(Long.parseLong(value));
               break;
            }
            case AWAIT_INITIAL_RETRIEVAL: {
               builder.topologyAwaitInitialTransfer(Boolean.parseBoolean(value));
               break;
            }
            case REPLICATION_TIMEOUT: {
               builder.topologyReplTimeout(Long.parseLong(value));
               break;
            }
            case LAZY_RETRIEVAL: {
               if (reader.getSchema().since(11, 0))
                  Server.log.warnHotRodLazyRetrievalDeprecated();
               builder.topologyStateTransfer(!Boolean.parseBoolean(value));
               break;
            }
            default: {
               throw ParseUtils.unexpectedAttribute(reader, i);
            }
         }
      }
      ParseUtils.requireNoContent(reader);
   }

}
