/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.cli.test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.CharsetUtil;
import org.apache.activemq.artemis.api.core.Pair;
import org.apache.activemq.artemis.cli.factory.xml.XmlBrokerFactoryHandler;
import org.apache.activemq.artemis.component.WebServerComponent;
import org.apache.activemq.artemis.component.WebServerComponentTestAccessor;
import org.apache.activemq.artemis.core.remoting.impl.ssl.SSLSupport;
import org.apache.activemq.artemis.core.server.ActiveMQComponent;
import org.apache.activemq.artemis.dto.AppDTO;
import org.apache.activemq.artemis.dto.BindingDTO;
import org.apache.activemq.artemis.dto.BrokerDTO;
import org.apache.activemq.artemis.dto.WebServerDTO;
import org.apache.activemq.artemis.utils.ThreadLeakCheckRule;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WebServerComponentTest extends Assert {

   @Rule
   public ThreadLeakCheckRule leakCheckRule = new ThreadLeakCheckRule();

   static final String URL = System.getProperty("url", "http://localhost:8161/WebServerComponentTest.txt");
   static final String SECURE_URL = System.getProperty("url", "https://localhost:8448/WebServerComponentTest.txt");
   private List<ActiveMQComponent> testedComponents;

   @Before
   public void setupNetty() throws URISyntaxException {
      System.setProperty("jetty.base", "./target");
      // Configure the client.
      testedComponents = new ArrayList<>();
   }

   @After
   public void tearDown() throws Exception {
      System.clearProperty("jetty.base");
      for (ActiveMQComponent c : testedComponents) {
         c.stop();
      }
      testedComponents.clear();
   }

   @Test
   public void simpleServer() throws Exception {
      internalSimpleServer(false);
   }

   @Test
   public void simpleServerWithCustomizer() throws Exception {
      internalSimpleServer(true);
   }

   private void internalSimpleServer(boolean useCustomizer) throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "http://localhost:0";
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "webapps";
      webServerDTO.webContentEnabled = true;
      if (useCustomizer) {
         webServerDTO.customizer = TestCustomizer.class.getName();
      }
      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      webServerComponent.configure(webServerDTO, "./src/test/resources/", "./src/test/resources/");
      testedComponents.add(webServerComponent);
      webServerComponent.start();
      final int port = webServerComponent.getPort();
      // Make the connection attempt.
      CountDownLatch latch = new CountDownLatch(1);
      final ClientHandler clientHandler = new ClientHandler(latch);
      Channel ch = getChannel(port, clientHandler);

      URI uri = new URI(URL);
      // Prepare the HTTP request.
      HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
      request.headers().set(HttpHeaderNames.HOST, "localhost");

      // Send the HTTP request.
      ch.writeAndFlush(request);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("12345", clientHandler.body.toString());
      if (useCustomizer) {
         assertEquals(1, TestCustomizer.count);
      }
      assertEquals(clientHandler.body.toString(), "12345");
      assertNull(clientHandler.serverHeader);
      // Wait for the server to close the connection.
      ch.close();
      ch.eventLoop().shutdownNow();
      Assert.assertTrue(webServerComponent.isStarted());
      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void testComponentStopBehavior() throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "http://localhost:0";
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "webapps";
      webServerDTO.webContentEnabled = true;
      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      webServerComponent.configure(webServerDTO, "./src/test/resources/", "./src/test/resources/");
      webServerComponent.start();
      // Make the connection attempt.
      verifyConnection(webServerComponent.getPort());
      Assert.assertTrue(webServerComponent.isStarted());

      //usual stop won't actually stop it
      webServerComponent.stop();
      assertTrue(webServerComponent.isStarted());

      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void testComponentStopStartBehavior() throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "http://localhost:0";
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "webapps";
      webServerDTO.webContentEnabled = true;
      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      webServerComponent.configure(webServerDTO, "./src/test/resources/", "./src/test/resources/");
      webServerComponent.start();
      // Make the connection attempt.
      verifyConnection(webServerComponent.getPort());
      Assert.assertTrue(webServerComponent.isStarted());

      //usual stop won't actually stop it
      webServerComponent.stop();
      assertTrue(webServerComponent.isStarted());

      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());

      webServerComponent.start();
      assertTrue(webServerComponent.isStarted());

      verifyConnection(webServerComponent.getPort());

      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void simpleSecureServer() throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "https://localhost:0";
      bindingDTO.keyStorePath = "./src/test/resources/server.keystore";
      bindingDTO.setKeyStorePassword("password");
      if (System.getProperty("java.vendor").contains("IBM")) {
         //By default on IBM Java 8 JVM, org.eclipse.jetty.util.ssl.SslContextFactory doesn't include TLSv1.2
         // while it excludes all TLSv1 and TLSv1.1 cipher suites.
         bindingDTO.setIncludedTLSProtocols("TLSv1.2");
         // Remove excluded cipher suites matching the prefix `SSL` because the names of the IBM Java 8 JVM cipher suites
         // have the prefix `SSL` while the `DEFAULT_EXCLUDED_CIPHER_SUITES` of org.eclipse.jetty.util.ssl.SslContextFactory
         // includes "^SSL_.*$". So all IBM JVM cipher suites are excluded by SslContextFactory using the `DEFAULT_EXCLUDED_CIPHER_SUITES`.
         bindingDTO.setExcludedCipherSuites(Arrays.stream(new SslContextFactory.Server().getExcludeCipherSuites()).filter(s -> !Pattern.matches(s, "SSL_")).toArray(String[]::new));
      }
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "webapps";
      webServerDTO.webContentEnabled = true;

      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      webServerComponent.configure(webServerDTO, "./src/test/resources/", "./src/test/resources/");
      testedComponents.add(webServerComponent);
      webServerComponent.start();
      final int port = webServerComponent.getPort();
      // Make the connection attempt.

      SSLContext context = new SSLSupport()
         .setKeystorePath(bindingDTO.keyStorePath)
         .setKeystorePassword(bindingDTO.getKeyStorePassword())
         .setTruststorePath(bindingDTO.keyStorePath)
         .setTruststorePassword(bindingDTO.getKeyStorePassword())
         .createContext();

      SSLEngine engine = context.createSSLEngine();
      engine.setUseClientMode(true);
      engine.setWantClientAuth(true);
      if (System.getProperty("java.vendor").contains("IBM")) {
         //By default on IBM Java 8 JVM, SSLEngine doesn't enable TLSv1.2 while
         // org.eclipse.jetty.util.ssl.SslContextFactory excludes all TLSv1 and TLSv1.1 cipher suites.
         engine.setEnabledProtocols(new String[] {"TLSv1.2"});
      }
      final SslHandler sslHandler = new SslHandler(engine);

      CountDownLatch latch = new CountDownLatch(1);
      final ClientHandler clientHandler = new ClientHandler(latch);

      Channel ch = getSslChannel(port, sslHandler, clientHandler);

      URI uri = new URI(SECURE_URL);
      // Prepare the HTTP request.
      HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
      request.headers().set(HttpHeaderNames.HOST, "localhost");

      // Send the HTTP request.
      ch.writeAndFlush(request);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("12345", clientHandler.body.toString());
      assertNull(clientHandler.serverHeader);
      // Wait for the server to close the connection.
      ch.close();
      ch.eventLoop().shutdownNow();
      Assert.assertTrue(webServerComponent.isStarted());
      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void simpleSecureServerWithClientAuth() throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "https://localhost:0";
      bindingDTO.keyStorePath = "./src/test/resources/server.keystore";
      bindingDTO.setKeyStorePassword("password");
      bindingDTO.clientAuth = true;
      bindingDTO.trustStorePath = "./src/test/resources/server.keystore";
      bindingDTO.setTrustStorePassword("password");
      if (System.getProperty("java.vendor").contains("IBM")) {
         //By default on IBM Java 8 JVM, org.eclipse.jetty.util.ssl.SslContextFactory doesn't include TLSv1.2
         // while it excludes all TLSv1 and TLSv1.1 cipher suites.
         bindingDTO.setIncludedTLSProtocols("TLSv1.2");
         // Remove excluded cipher suites matching the prefix `SSL` because the names of the IBM Java 8 JVM cipher suites
         // have the prefix `SSL` while the `DEFAULT_EXCLUDED_CIPHER_SUITES` of org.eclipse.jetty.util.ssl.SslContextFactory
         // includes "^SSL_.*$". So all IBM JVM cipher suites are excluded by SslContextFactory using the `DEFAULT_EXCLUDED_CIPHER_SUITES`.
         bindingDTO.setExcludedCipherSuites(Arrays.stream(new SslContextFactory.Server().getExcludeCipherSuites()).filter(s -> !Pattern.matches(s, "SSL_")).toArray(String[]::new));
      }
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "webapps";
      webServerDTO.webContentEnabled = true;

      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      webServerComponent.configure(webServerDTO, "./src/test/resources/", "./src/test/resources/");
      testedComponents.add(webServerComponent);
      webServerComponent.start();
      final int port = webServerComponent.getPort();
      // Make the connection attempt.

      SSLContext context = new SSLSupport()
         .setKeystorePath(bindingDTO.keyStorePath)
         .setKeystorePassword(bindingDTO.getKeyStorePassword())
         .setTruststorePath(bindingDTO.trustStorePath)
         .setTruststorePassword(bindingDTO.getTrustStorePassword())
         .createContext();

      SSLEngine engine = context.createSSLEngine();
      engine.setUseClientMode(true);
      engine.setWantClientAuth(true);
      if (System.getProperty("java.vendor").contains("IBM")) {
         //By default on IBM Java 8 JVM, SSLEngine doesn't enable TLSv1.2 while
         // org.eclipse.jetty.util.ssl.SslContextFactory excludes all TLSv1 and TLSv1.1 cipher suites.
         engine.setEnabledProtocols(new String[] {"TLSv1.2"});
      }
      final SslHandler sslHandler = new SslHandler(engine);

      CountDownLatch latch = new CountDownLatch(1);
      final ClientHandler clientHandler = new ClientHandler(latch);
      Channel ch = getSslChannel(port, sslHandler, clientHandler);

      URI uri = new URI(SECURE_URL);
      // Prepare the HTTP request.
      HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
      request.headers().set(HttpHeaderNames.HOST, "localhost");

      // Send the HTTP request.
      ch.writeAndFlush(request);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("12345", clientHandler.body.toString());
      // Wait for the server to close the connection.
      ch.close();
      ch.eventLoop().shutdownNow();
      Assert.assertTrue(webServerComponent.isStarted());
      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void testDefaultMaskPasswords() throws Exception {
      File bootstrap = new File("./target/test-classes/bootstrap_web.xml");
      File brokerHome = new File("./target");
      XmlBrokerFactoryHandler xmlHandler = new XmlBrokerFactoryHandler();
      BrokerDTO broker = xmlHandler.createBroker(bootstrap.toURI(), brokerHome.getAbsolutePath(), brokerHome.getAbsolutePath(), brokerHome.toURI());
      assertNotNull(broker.web);
      assertNull(broker.web.getDefaultBinding().passwordCodec);
   }

   @Test
   public void testMaskPasswords() throws Exception {
      final String keyPassword = "keypass";
      final String trustPassword = "trustpass";
      File bootstrap = new File("./target/test-classes/bootstrap_secure_web.xml");
      File brokerHome = new File("./target");
      XmlBrokerFactoryHandler xmlHandler = new XmlBrokerFactoryHandler();
      BrokerDTO broker = xmlHandler.createBroker(bootstrap.toURI(), brokerHome.getAbsolutePath(), brokerHome.getAbsolutePath(), brokerHome.toURI());
      assertNotNull(broker.web);
      assertEquals(keyPassword, broker.web.getDefaultBinding().getKeyStorePassword());
      assertEquals(trustPassword, broker.web.getDefaultBinding().getTrustStorePassword());
   }

   @Test
   public void testMaskPasswordCodec() throws Exception {
      final String keyPassword = "keypass";
      final String trustPassword = "trustpass";
      File bootstrap = new File("./target/test-classes/bootstrap_web_codec.xml");
      File brokerHome = new File("./target");
      XmlBrokerFactoryHandler xmlHandler = new XmlBrokerFactoryHandler();
      BrokerDTO broker = xmlHandler.createBroker(bootstrap.toURI(), brokerHome.getAbsolutePath(), brokerHome.getAbsolutePath(), brokerHome.toURI());
      assertNotNull(broker.web);
      assertNotNull("password codec not picked up!", broker.web.getDefaultBinding().passwordCodec);

      assertEquals(keyPassword, broker.web.getDefaultBinding().getKeyStorePassword());
      assertEquals(trustPassword, broker.web.getDefaultBinding().getTrustStorePassword());
   }

   @Test
   public void testOldConfigurationStyle() throws Exception {
      final String keyPassword = "keypass";
      final String trustPassword = "trustpass";

      File bootstrap = new File("./target/test-classes/bootstrap_web_old_config.xml");
      File brokerHome = new File("./target");
      XmlBrokerFactoryHandler xmlHandler = new XmlBrokerFactoryHandler();
      BrokerDTO broker = xmlHandler.createBroker(bootstrap.toURI(), brokerHome.getAbsolutePath(), brokerHome.getAbsolutePath(), brokerHome.toURI());
      assertNotNull(broker.web);
      assertNotNull("password codec not picked up!", broker.web.getDefaultBinding().passwordCodec);

      assertEquals("http://localhost:8161", broker.web.getDefaultBinding().uri);
      assertEquals(keyPassword, broker.web.getDefaultBinding().getKeyStorePassword());
      assertEquals(trustPassword, broker.web.getDefaultBinding().getTrustStorePassword());
   }

   @Test
   public void testServerCleanupBeforeStart() throws Exception {
      final String warName = "simple-app.war";
      createTestWar(warName);
      final String url = "simple-app/";

      AppDTO app = new AppDTO();
      app.url = url;
      app.war = warName;
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "http://localhost:0";
      bindingDTO.apps = new ArrayList<>();
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "";
      WebServerComponent webServerComponent = new WebServerComponent();
      Assert.assertFalse(webServerComponent.isStarted());
      testedComponents.add(webServerComponent);
      webServerComponent.configure(webServerDTO, "./target", "./target");
      WebAppContext ctxt = WebServerComponentTestAccessor.createWebAppContext(webServerComponent, url, warName, Paths.get(".").resolve("target").toAbsolutePath(), null);
      webServerComponent.getWebContextData().add(new Pair(ctxt, null));

      WebInfConfiguration cfg = new WebInfConfiguration();
      List<File> garbage = new ArrayList<>();

      cfg.resolveTempDirectory(ctxt);

      File tmpdir = ctxt.getTempDirectory();
      File testDir = tmpdir.getParentFile();

      createGarbagesInDir(testDir, garbage);

      assertTrue(garbage.size() > 0);

      for (File file : garbage) {
         assertTrue(file.exists());
      }

      webServerComponent.start();

      //make sure those garbage are gone
      for (File file : garbage) {
         assertFalse("file exist: " + file.getAbsolutePath(), file.exists());
      }
      Assert.assertTrue(webServerComponent.isStarted());
      webServerComponent.stop(true);
      Assert.assertFalse(webServerComponent.isStarted());
   }

   @Test
   public void testDefaultRootRedirect() throws Exception {
      testRootRedirect(null, 404, null);
   }

   @Test
   public void testCustomRootRedirect() throws Exception {
      testRootRedirect("test-root-redirect", 302, "test-root-redirect");
   }

   public void testRootRedirect(String rootRedirectLocation, int expectedResponseCode, String expectedResponseLocation) throws Exception {
      BindingDTO bindingDTO = new BindingDTO();
      bindingDTO.uri = "http://localhost:0";
      WebServerDTO webServerDTO = new WebServerDTO();
      webServerDTO.setBindings(Collections.singletonList(bindingDTO));
      webServerDTO.path = "";
      webServerDTO.rootRedirectLocation = rootRedirectLocation;

      WebServerComponent webServerComponent = new WebServerComponent();
      webServerComponent.configure(webServerDTO, null, null);
      webServerComponent.start();
      try {
         int port = webServerComponent.getPort(0);
         java.net.URL url = new URL("http://localhost:" + port);
         HttpURLConnection conn = (HttpURLConnection) url.openConnection();
         conn.setInstanceFollowRedirects(false);
         try {
            assertEquals(expectedResponseCode, conn.getResponseCode());
            if (expectedResponseLocation != null) {
               assertTrue(conn.getHeaderField("Location").endsWith(webServerDTO.rootRedirectLocation));
            }
         } finally {
            conn.disconnect();
         }
      } finally {
         webServerComponent.stop(true);
      }
   }

   private void createTestWar(String warName) throws Exception {
      File warFile = new File("target", warName);
      File srcFile = new File("src/test/webapp");
      createJarFile(srcFile, warFile);
   }

   private void createJarFile(File srcFile, File jarFile) throws IOException {
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      try (JarOutputStream target = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
         addFile(srcFile, target, "src/test/webapp");
      }
   }

   private void addFile(File source, JarOutputStream target, String nameBase) throws IOException {
      if (source.isDirectory()) {
         String name = source.getPath().replace("\\", "/");
         if (!name.isEmpty()) {
            name = name.substring(nameBase.length());
            if (!name.endsWith("/")) {
               name += "/";
            }
            JarEntry entry = new JarEntry(name);
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            target.closeEntry();
         }
         for (File nestedFile: source.listFiles()) {
            addFile(nestedFile, target, nameBase);
         }
         return;
      }

      String name = source.getPath().replace("\\", "/");
      name = name.substring(nameBase.length());
      JarEntry entry = new JarEntry(name);
      entry.setTime(source.lastModified());
      target.putNextEntry(entry);
      try (BufferedInputStream input  = new BufferedInputStream(new FileInputStream(source))) {
         byte[] buffer = new byte[1024];
         while (true) {
            int count = input.read(buffer);
            if (count == -1)
               break;
            target.write(buffer, 0, count);
         }
         target.closeEntry();
      }
   }

   private void createGarbagesInDir(File tempDirectory, List<File> garbage) throws IOException {
      if (!tempDirectory.exists()) {
         tempDirectory.mkdirs();
      }
      createRandomJettyFiles(tempDirectory, 10, garbage);
   }

   private void createRandomJettyFiles(File dir, int num, List<File> collector) throws IOException {
      for (int i = 0; i < num; i++) {
         String randomName = "jetty-" + UUID.randomUUID().toString();
         File file = new File(dir, randomName);
         if (i % 2 == 0) {
            //create a dir
            file.mkdir();
         } else {
            //normal file
            file.createNewFile();
         }
         collector.add(file);
      }
   }

   private Channel getChannel(int port, ClientHandler clientHandler) throws InterruptedException {
      EventLoopGroup group = new NioEventLoopGroup();
      Bootstrap bootstrap = new Bootstrap();
      bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer() {
         @Override
         protected void initChannel(Channel ch) throws Exception {
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(clientHandler);
         }
      });
      return bootstrap.connect("localhost", port).sync().channel();
   }

   private Channel getSslChannel(int port, SslHandler sslHandler, ClientHandler clientHandler) throws InterruptedException {
      EventLoopGroup group = new NioEventLoopGroup();
      Bootstrap bootstrap = new Bootstrap();
      bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer() {
         @Override
         protected void initChannel(Channel ch) throws Exception {
            ch.pipeline().addLast(sslHandler);
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(clientHandler);
         }
      });
      return bootstrap.connect("localhost", port).sync().channel();
   }

   private void verifyConnection(int port) throws InterruptedException, URISyntaxException {
      CountDownLatch latch = new CountDownLatch(1);
      final ClientHandler clientHandler = new ClientHandler(latch);
      Channel ch = getChannel(port, clientHandler);

      URI uri = new URI(URL);
      // Prepare the HTTP request.
      HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath());
      request.headers().set(HttpHeaderNames.HOST, "localhost");

      // Send the HTTP request.
      ch.writeAndFlush(request);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("12345", clientHandler.body.toString());
      // Wait for the server to close the connection.
      ch.close();
      ch.eventLoop().shutdownNow();
   }

   class ClientHandler extends SimpleChannelInboundHandler<HttpObject> {

      private CountDownLatch latch;
      private StringBuilder body = new StringBuilder();
      private String serverHeader;

      ClientHandler(CountDownLatch latch) {
         this.latch = latch;
      }

      @Override
      public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
         if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            serverHeader = response.headers().get("Server");
         } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            body.append(content.content().toString(CharsetUtil.UTF_8));
            if (msg instanceof LastHttpContent) {
               latch.countDown();
            }
         }
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
         cause.printStackTrace();
         ctx.close();
      }
   }
}
