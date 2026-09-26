package forge.net;

import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gamemodes.net.sharedart.SharedArtSession;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The shared-art capability handshake over the real server and client
 * pipelines: the client's login trailer reaches the host, the host's answer
 * reaches the client, and a login without the trailer, which is what an
 * older client sends, is never answered with one. A picture round trip is not
 * covered here; see the commit message. Uses the headless network harness,
 * which lives in this module.
 */
public class SharedArtHandshakeTest {

    @BeforeClass
    public void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    private interface Check {
        void run(HeadlessNetworkClient remote, RemoteClient seat) throws Exception;
    }

    private static void logIn(final boolean show, final boolean share, final Check check) throws Exception {
        final ForgePreferences prefs = FModel.getPreferences();
        final boolean savedShow = prefs.getPrefBoolean(FPref.UI_NETPLAY_SHOW_SHARED_ART);
        final boolean savedShare = prefs.getPrefBoolean(FPref.UI_NETPLAY_SHARE_CUSTOM_ART);
        prefs.setPref(FPref.UI_NETPLAY_SHOW_SHARED_ART, show);
        prefs.setPref(FPref.UI_NETPLAY_SHARE_CUSTOM_ART, share);
        final FServerManager server = FServerManager.getInstance();
        HeadlessNetworkClient remote = null;
        try {
            final int port = PortAllocator.allocatePort();
            server.startServer(port);
            server.setLobby(new ServerGameLobby());

            remote = new HeadlessNetworkClient("ArtPeer", "localhost", port);
            Assert.assertTrue(remote.connect(15_000), "the client must log in");
            final RemoteClient seat = server.findClientByIndex(remote.getAssignedSlot());
            Assert.assertNotNull(seat);
            check.run(remote, seat);
        } finally {
            if (remote != null) {
                remote.close();
            }
            if (HostingServer.isHosting()) {
                server.stopServer();
            }
            prefs.setPref(FPref.UI_NETPLAY_SHOW_SHARED_ART, savedShow);
            prefs.setPref(FPref.UI_NETPLAY_SHARE_CUSTOM_ART, savedShare);
        }
    }

    @Test(timeOut = 60_000)
    public void testClientAndHostNegotiateOverTheRealPipeline() throws Exception {
        logIn(true, false, (remote, seat) -> {
            final SharedArtSession art = remote.getClient().getSharedArt();
            final long deadline = System.currentTimeMillis() + 10_000;
            while (!art.isPeerCapable() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertTrue(art.isPeerCapable(), "the host's answer must reach the client");
            Assert.assertTrue(seat.supportsSharedArt(), "the client's login trailer must reach the host");
            Assert.assertFalse(seat.servesSharedArt(), "a player who only shows is never asked for pictures");
        });
    }

    @Test(timeOut = 60_000)
    public void testALoginWithoutTheTrailerIsNeverAnsweredWithOne() throws Exception {
        // With both switches off the login is byte for byte an older client's. The host answers the
        // login before it sends the lobby state, and the client has that state once it has its seat.
        logIn(false, false, (remote, seat) -> {
            Assert.assertFalse(seat.supportsSharedArt(), "no trailer reached the host");
            Thread.sleep(500);
            Assert.assertFalse(remote.getClient().getSharedArt().isPeerCapable(),
                    "the host sent no capabilities, which an older client could not read");
        });
    }
}
