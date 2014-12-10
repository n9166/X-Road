package ee.cyber.sdsb.proxy.antidos;

import java.util.HashSet;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import ee.cyber.sdsb.common.conf.globalconf.EmptyGlobalConf;
import ee.cyber.sdsb.common.conf.globalconf.GlobalConf;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AntiDosConnectionManagerTest {

    private static final Set<String> KNOWN_ADDRESSES = new HashSet<>();
    static {
        KNOWN_ADDRESSES.add("test1");
        KNOWN_ADDRESSES.add("test2");
        KNOWN_ADDRESSES.add("test3");
    }

    @BeforeClass
    public static void reloadGlobalConf() {
        GlobalConf.reload(new EmptyGlobalConf() {
            @Override
            public Set<String> getKnownAddresses() {
                return KNOWN_ADDRESSES;
            }
        });
    }

    // ------------------------------------------------------------------------

    @Test
    public void normalLoad() throws Exception {
        TestConfiguration conf = new TestConfiguration(15, 0.5);

        TestSystemMetrics sm = new TestSystemMetrics();
        sm.addLoad(20, 0.1);

        TestSocketChannel member1 = createConnection("test1");
        TestSocketChannel member2 = createConnection("test2");
        TestSocketChannel member3 = createConnection("test3");

        TestConnectionManager cm = createConnectionManager(conf, sm);
        // two connections from member2
        cm.accept(member1, member2, member2, member3);

        // member3 should get next connection after member2's frist connection
        cm.assertConnections(member1, member2, member3, member2);

        cm.assertEmpty();
    }

    @Test
    public void outOfFileHandles() throws Exception {
        TestConfiguration conf = new TestConfiguration(5, 1.1);

        TestSystemMetrics sm = new TestSystemMetrics();
        sm.addLoad(7, 0.1);
        sm.addLoad(6, 0.1);
        sm.addLoad(3, 0.1);

        TestSocketChannel member1 = createConnection("test1");
        TestSocketChannel member2 = createConnection("test2");

        TestConnectionManager cm = createConnectionManager(conf, sm);
        cm.accept(member1, member2, member2);

        cm.assertConnections(member1, member2);

        assertNull(cm.getNextConnection());
        assertTrue(member2.isClosed());

        cm.assertEmpty();
    }

    @Test
    public void knownMembersCanConnectUnderDosAttack() throws Exception {
        TestConfiguration conf = new TestConfiguration(5, 1.1);

        TestSystemMetrics sm = new TestSystemMetrics();
        sm.addLoad(7, 0.1);

        TestSocketChannel member1 = createConnection("test1");
        TestSocketChannel member2 = createConnection("test2");

        TestSocketChannel attacker1 = createConnection("attacker1");
        TestSocketChannel attacker2 = createConnection("attacker2");

        TestConnectionManager cm = createConnectionManager(conf, sm);

        cm.accept(
                member1,
                attacker1,
                member2,
                attacker1,
                attacker2,
                attacker2,
                member2);

        // We should get interleaved connections between members and attackers
        cm.assertConnections(
                member1,
                attacker1,
                member2,
                attacker1,
                member2,
                attacker2,
                attacker2);

        cm.assertEmpty();
    }

    // ------------------------------------------------------------------------

    private static TestConnectionManager createConnectionManager(
            TestConfiguration configuration, TestSystemMetrics systemMetrics)
                throws Exception {
        TestConnectionManager connectionManager =
                new TestConnectionManager(configuration, systemMetrics);
        connectionManager.init();
        return connectionManager;
    }

    private static TestSocketChannel createConnection(String address) {
        return new TestSocketChannel(address);
    }
}
