/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.channel.jdbc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.channel.jdbc.impl.JdbcChannelProviderImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestJdbcChannelProvider {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TestJdbcChannelProvider.class);

  private Properties derbyProps = new Properties();
  private File derbyDbDir;
  private JdbcChannelProviderImpl provider;

  @Before
  public void setUp() throws IOException {
    derbyProps.clear();
    derbyProps.put(ConfigurationConstants.CONFIG_CREATE_SCHEMA, "true");
    derbyProps.put(ConfigurationConstants.CONFIG_DATABASE_TYPE, "DERBY");
    derbyProps.put(ConfigurationConstants.CONFIG_JDBC_DRIVER_CLASS,
        "org.apache.derby.jdbc.EmbeddedDriver");

    derbyProps.put(ConfigurationConstants.CONFIG_PASSWORD, "");
    derbyProps.put(ConfigurationConstants.CONFIG_USERNAME, "sa");

    File tmpDir = new File("target/test");
    tmpDir.mkdirs();

    // Use a temp file to create a temporary directory
    File tempFile = File.createTempFile("temp", "_db", tmpDir);
    String absFileName = tempFile.getCanonicalPath();
    tempFile.delete();

    derbyDbDir = new File(absFileName + "_dir");

    if (!derbyDbDir.exists()) {
      derbyDbDir.mkdirs();
    }

    derbyProps.put(ConfigurationConstants.CONFIG_URL,
        "jdbc:derby:" + derbyDbDir.getCanonicalPath() + "/db;create=true");

    LOGGER.info("Derby Properties: " + derbyProps);
  }

  @Test
  public void testDerbySetup() {
    provider = new JdbcChannelProviderImpl();

    provider.initialize(derbyProps);

    Transaction tx1 = provider.getTransaction();
    tx1.begin();

    Transaction tx2 = provider.getTransaction();

    Assert.assertSame(tx1, tx2);
    tx2.begin();
    tx2.close();
    tx1.close();

    Transaction tx3 = provider.getTransaction();
    Assert.assertNotSame(tx1, tx3);

    tx3.begin();
    tx3.close();

    provider.close();
    provider = null;
  }

  /**
   * creaes 1000 events split over 5 channels, stores them
   */
  @Test
  public void testPeristingEvents() {
    provider = new JdbcChannelProviderImpl();
    provider.initialize(derbyProps);

    Map<String, List<MockEvent>> eventMap =
        new HashMap<String, List<MockEvent>>();

    Set<MockEvent> events = new HashSet<MockEvent>();
    for (int i = 1; i < 1001; i++) {
      events.add(MockEventUtils.generateMockEvent(i, i, i, 61%i, 5));
    }

    Iterator<MockEvent> meIt = events.iterator();
    while (meIt.hasNext()) {
      MockEvent me = meIt.next();
      String chName = me.getChannel();
      List<MockEvent> eventList = eventMap.get(chName);
      if (eventList == null) {
        eventList = new ArrayList<MockEvent>();
        eventMap.put(chName, eventList);
      }
      eventList.add(me);
      provider.persistEvent(me.getChannel(), me);
    }

    // Now retrieve the events and they should be in the persistence order

    for (String chName : eventMap.keySet()) {
      List<MockEvent> meList = eventMap.get(chName);
      Iterator<MockEvent> it = meList.iterator();
      while (it.hasNext()) {
        MockEvent me = it.next();
        Event event = provider.removeEvent(chName);
        assertEquals(me, event);
      }

      // Now the there should be no more events for this channel
      Event nullEvent = provider.removeEvent(chName);
      Assert.assertNull(nullEvent);
    }

    provider.close();
    provider = null;
  }

  private void assertEquals(Event e1, Event e2) {
    byte[] pl1 = e1.getBody();
    byte[] pl2 = e2.getBody();

    Assert.assertArrayEquals(pl1, pl2);
    Map<String, String> h1 = e1.getHeaders();
    Map<String, String> h2 = e2.getHeaders();
    if (h1 == null || h1.size() == 0) {
      Assert.assertTrue(h2 == null || h2.size() == 0);
    } else {
      Assert.assertTrue(h1.size() == h2.size());
      for (String key : h1.keySet()) {
        Assert.assertTrue(h2.containsKey(key));
        String v1 = h1.get(key);
        String v2 = h2.remove(key);
        Assert.assertEquals(v1, v2);
      }
      Assert.assertTrue(h2.size() == 0);
    }
  }

  @After
  public void tearDown() throws IOException {
    if (provider != null) {
      try {
        provider.close();
      } catch (Exception ex) {
        LOGGER.error("Unable to close provider", ex);
      }
    }
    provider = null;
  }
}