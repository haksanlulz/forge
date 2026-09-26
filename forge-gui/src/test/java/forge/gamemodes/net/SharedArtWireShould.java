package forge.gamemodes.net;

import forge.gamemodes.net.event.ArtReplyEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.NetCapabilities;
import forge.gamemodes.net.sharedart.SharedArtPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolver;
import io.netty.handler.codec.serialization.ClassResolvers;
import net.jpountz.lz4.LZ4BlockInputStream;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The shared-art capability rides in the login frame without changing any
 * class an older peer reads, so mixed-version lobbies keep logging in. Shares
 * the codec's package on purpose.
 */
public class SharedArtWireShould {

    /** Stands in for an older build: it has every class except the capability trailer's. */
    private static final ClassResolver WITHOUT_CAPABILITIES = name -> {
        if (NetCapabilities.class.getName().equals(name)) {
            throw new ClassNotFoundException(name);
        }
        return Class.forName(name, false, SharedArtWireShould.class.getClassLoader());
    };

    private static LoginEvent login(final NetCapabilities caps) {
        final LoginEvent event = new LoginEvent("Alice", 1, 2, "2.0.0-TEST", false);
        event.setCapabilities(caps);
        return event;
    }

    private static ByteBuf encode(final LoginEvent event) throws Exception {
        return new CompatibleObjectEncoder(null).encodeToBuf(event, UnpooledByteBufAllocator.DEFAULT);
    }

    private static LoginEvent decode(final ByteBuf frame, final ClassResolver resolver) {
        final EmbeddedChannel channel = new EmbeddedChannel(new CompatibleObjectDecoder(9766 * 1024, resolver));
        try {
            channel.writeInbound(frame);
            return channel.readInbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Exactly what every decoder before this change does: one object per frame, then close. */
    private static Object readOneObject(final ByteBuf frame, final ClassResolver resolver) throws Exception {
        frame.skipBytes(4);
        try (ObjectInputStream in = new CObjectInputStream(
                new LZ4BlockInputStream(new ByteBufInputStream(frame, true)), resolver, null)) {
            return in.readObject();
        }
    }

    @Test
    public void keepTheLoginCapabilitiesInvisibleToOlderPeers() throws Exception {
        // Older peers read LoginEvent with their own field layout (descriptors are
        // thin), so its serialized fields must never change.
        final List<String> fields = new ArrayList<>();
        for (final ObjectStreamField field : ObjectStreamClass.lookup(LoginEvent.class).getFields()) {
            fields.add(field.getName());
        }
        fields.sort(null);
        Assert.assertEquals(fields, Arrays.asList("avatarIndex", "libgdx", "sleeveIndex", "username", "version"));

        // An older host reads the login and never resolves the trailer's class.
        final Object old = readOneObject(encode(login(NetCapabilities.local())), WITHOUT_CAPABILITIES);
        Assert.assertTrue(old instanceof LoginEvent, "an older host must still read the login");
        Assert.assertEquals(((LoginEvent) old).getUsername(), "Alice");

        // A current host reads the trailer.
        final LoginEvent announced = decode(encode(login(NetCapabilities.local())), ClassResolvers.cacheDisabled(null));
        Assert.assertNotNull(announced.getCapabilities(), "the capability trailer must arrive");
        Assert.assertTrue(announced.getCapabilities().has(NetCapabilities.SHARED_ART));

        // A login from an older client carries no trailer.
        final LoginEvent plain = decode(encode(login(null)), ClassResolvers.cacheDisabled(null));
        Assert.assertEquals(plain.getUsername(), "Alice");
        Assert.assertNull(plain.getCapabilities());

        // A trailer this build cannot read never costs the login.
        final LoginEvent unreadable = decode(encode(login(NetCapabilities.local())), WITHOUT_CAPABILITIES);
        Assert.assertNotNull(unreadable, "an unreadable trailer must not drop the login");
        Assert.assertEquals(unreadable.getUsername(), "Alice");
        Assert.assertNull(unreadable.getCapabilities());
    }

    @Test
    public void checkAnArtReplyBeforeAllocatingIt() throws Exception {
        // The real codec, thin descriptors and all, carries a picture and a refusal.
        final byte[] picture = new byte[SharedArtPolicy.CHUNK_BYTES + 10];
        final EmbeddedChannel channel = new EmbeddedChannel(new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)));
        try {
            channel.writeInbound(new CompatibleObjectEncoder(null).encodeToBuf(
                    new ArtReplyEvent(7, "c:A|XCUS|1", SharedArtPolicy.split(picture)), UnpooledByteBufAllocator.DEFAULT));
            final ArtReplyEvent sent = channel.readInbound();
            Assert.assertEquals(sent.getRequestId(), 7);
            Assert.assertEquals(sent.getImageKey(), "c:A|XCUS|1");
            Assert.assertEquals(sent.getChunks()[0].length + sent.getChunks()[1].length, picture.length);
            channel.writeInbound(new CompatibleObjectEncoder(null).encodeToBuf(
                    new ArtReplyEvent(8, "c:A|XCUS|1", null), UnpooledByteBufAllocator.DEFAULT));
            Assert.assertNull(((ArtReplyEvent) channel.readInbound()).getChunks());
        } finally {
            channel.finishAndReleaseAll();
        }

        // A piece declared at 2 MB is refused from its length, not read: the stream
        // holds four bytes, so reading would end in EOFException instead.
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(raw)) {
            out.writeObject(new ArtReplyEvent(1, "c:A|XCUS|1", new byte[][]{{0x7A, 0x7B, 0x7C, 0x7D}}));
        }
        final byte[] bytes = raw.toByteArray();
        final byte[] piece = {0, 0, 0, 4, 0x7A, 0x7B, 0x7C, 0x7D};
        final int at = indexOf(bytes, piece);
        Assert.assertTrue(at > 0);
        final int declared = 2_000_000;
        bytes[at] = (byte) (declared >>> 24);
        bytes[at + 1] = (byte) (declared >>> 16);
        bytes[at + 2] = (byte) (declared >>> 8);
        bytes[at + 3] = (byte) declared;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.readObject();
            Assert.fail("an over-cap piece must be refused");
        } catch (final InvalidClassException expected) {
            // what CompatibleObjectDecoder drops without closing the channel
        }
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return i;
            }
        }
        return -1;
    }
}
