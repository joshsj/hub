package com.flightstats.hub.cluster;

import com.flightstats.hub.test.Integration;
import com.flightstats.hub.util.Sleeper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CuratorLeaderIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CuratorLeaderIntegrationTest.class);
    private AtomicInteger count;
    private CountDownLatch countDownLatch;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Integration.startAwsHub();
    }

    @Test
    public void testElection() throws Exception {
        count = new AtomicInteger();
        countDownLatch = new CountDownLatch(1);
        CuratorLeader curatorLeader = new CuratorLeader("/CuratorLeaderIntegrationTest/testElection",
                new MockLeader());

        curatorLeader.start();
        assertTrue(countDownLatch.await(5000, TimeUnit.MILLISECONDS));
        assertEquals(1, count.get());
        curatorLeader.close();
    }

    @Test
    public void testMultipleLeaders() throws Exception {
        count = new AtomicInteger();
        countDownLatch = new CountDownLatch(3);
        String leaderPath = "/CuratorLeaderIntegrationTest/testMultipleLeaders";
        CuratorLeader curatorLeader1 = new CuratorLeader(leaderPath, new MockLeader());
        CuratorLeader curatorLeader2 = new CuratorLeader(leaderPath, new MockLeader());
        CuratorLeader curatorLeader3 = new CuratorLeader(leaderPath, new MockLeader());

        curatorLeader1.start();
        curatorLeader2.start();
        curatorLeader3.start();


        assertTrue(countDownLatch.await(5000, TimeUnit.MILLISECONDS));

        assertEquals(3, count.get());
        curatorLeader1.close();
        curatorLeader2.close();
        curatorLeader3.close();
    }

    @Test
    public void testMultipleStarts() throws Exception {
        count = new AtomicInteger();
        countDownLatch = new CountDownLatch(5);

        CuratorLeader curatorLeader = new CuratorLeader("/CuratorLeaderIntegrationTest/testMultipleStarts", new MockLeader());
        for (int i = 0; i < 5; i++) {
            curatorLeader.start();
        }
        assertTrue(countDownLatch.await(5000, TimeUnit.MILLISECONDS));

        assertEquals(5, count.get());
        curatorLeader.close();
    }

    @Test
    public void testClose() throws Exception {
        count = new AtomicInteger();
        countDownLatch = new CountDownLatch(1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CuratorLeader curatorLeader = new CuratorLeader("/testClose", new CloseLeader(startLatch));

        curatorLeader.start();
        assertTrue(startLatch.await(2000, TimeUnit.MILLISECONDS));
        curatorLeader.close();
        assertTrue(countDownLatch.await(2000, TimeUnit.MILLISECONDS));
    }

   /* @Test
    public void testLimitChildren() throws Exception {
        CuratorFramework curator = HubBindings.buildCurator("hub-v2", "local", "localhost:2181", new ZooKeeperState());
        String leaderPath = "/testLimitChildren";
        CuratorLeader leader = new CuratorLeader(leaderPath, new MockLeader());
        curator.create().creatingParentsIfNeeded().forPath(leaderPath);
        curator.create().forPath(leaderPath + "/OneA", "1.1.1.1".getBytes());
        curator.create().forPath(leaderPath + "/TwoA", "2.2.2.2".getBytes());
        Sleeper.sleep(10);
        curator.create().forPath(leaderPath + "/OneB", "1.1.1.1".getBytes());
        curator.create().forPath(leaderPath + "/TwoB", "2.2.2.2".getBytes());
        leader.limitChildren(2);
        List<String> children = curator.getChildren().forPath(leaderPath);
        assertEquals(2, children.size());
        assertTrue(children.contains("OneB"));
        assertTrue(children.contains("TwoB"));

    }*/

    private class MockLeader implements Leader {

        @Override
        public void takeLeadership(AtomicBoolean hasLeadership) {
            logger.info("do Work");
            Sleeper.sleep(5);
            count.incrementAndGet();
            countDownLatch.countDown();
        }
    }

    private class CloseLeader implements Leader {

        private final CountDownLatch startLatch;

        CloseLeader(CountDownLatch startLatch) {

            this.startLatch = startLatch;
        }

        @Override
        public void takeLeadership(AtomicBoolean hasLeadership) {
            startLatch.countDown();
            while (hasLeadership.get()) {
                Sleeper.sleepQuietly(5);
            }
            countDownLatch.countDown();
        }
    }
}
