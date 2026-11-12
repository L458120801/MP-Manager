package io.github.abdurazaaqmohammed.features.files.network;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

final class SmbShareEnumerator {
    private static final byte[] SRVSVC_UUID = {
            (byte) 0xc8, 0x4f, 0x32, 0x4b, 0x70, 0x16, (byte) 0xd3, 0x01,
            0x12, 0x78, 0x5a, 0x47, (byte) 0xbf, 0x6e, (byte) 0xe1, (byte) 0x88
    };
    private static final byte[] NDR_UUID = {
            0x04, 0x5d, (byte) 0x88, (byte) 0x8a, (byte) 0xeb, 0x1c, (byte) 0xc9, 0x11,
            (byte) 0x9f, (byte) 0xe8, 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60
    };
    private static final int ERROR_MORE_DATA = 234;

    private SmbShareEnumerator() {}

    static List<String> enumerate(Session session, String host) throws IOException {
        try (PipeShare ipc = (PipeShare) session.connectShare("IPC$");
             NamedPipe pipe = ipc.open("srvsvc", SMB2ImpersonationLevel.Impersonation,
                     EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                     EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                     SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
            requireBind(pipe.transact(bindPacket()));
            List<String> names = new ArrayList<>();
            int resume = 0;
            int callId = 2;
            while (true) {
                SharePage page = decodePage(responseStub(pipe, pipe.transact(requestPacket(host, resume, callId)), callId));
                for (ShareInfo share : page.shares) {
                    if ((share.type & 0xff) == 0 && !share.name.equalsIgnoreCase("IPC$")) names.add(share.name);
                }
                if (page.status == 0) return names;
                if (page.status != ERROR_MORE_DATA) throw new IOException("SMB share enumeration failed with status 0x" + Integer.toHexString(page.status));
                if (page.resume == resume) throw new IOException("SMB server returned an incomplete share page without advancing its resume handle");
                resume = page.resume;
                callId++;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SMB share enumeration failed: " + e.getMessage(), e);
        }
    }

    private static byte[] bindPacket() {
        ByteBuffer packet = rpcPacket(11, 1, 56);
        packet.putShort((short) 4280).putShort((short) 4280).putInt(0);
        packet.put((byte) 1).put((byte) 0).putShort((short) 0);
        packet.putShort((short) 0).put((byte) 1).put((byte) 0);
        packet.put(SRVSVC_UUID).putShort((short) 3).putShort((short) 0);
        packet.put(NDR_UUID).putShort((short) 2).putShort((short) 0);
        return packet.array();
    }

    private static byte[] requestPacket(String host, int resume, int callId) {
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        String serverName = "\\\\" + host + '\0';
        putInt(stub, 0x20000);
        putInt(stub, serverName.length());
        putInt(stub, 0);
        putInt(stub, serverName.length());
        for (int i = 0; i < serverName.length(); i++) putShort(stub, serverName.charAt(i));
        while ((stub.size() & 3) != 0) stub.write(0);
        putInt(stub, 1); // SHARE_ENUM_STRUCT.Level
        putInt(stub, 1); // SHARE_ENUM_UNION tag
        putInt(stub, 0x20004); // SHARE_INFO_1_CONTAINER pointer
        putInt(stub, 0); // EntriesRead
        putInt(stub, 0); // Buffer pointer
        putInt(stub, -1); // MAX_PREFERRED_LENGTH
        putInt(stub, 0x20008); // ResumeHandle pointer
        putInt(stub, resume);

        ByteBuffer packet = rpcPacket(0, callId, stub.size() + 8);
        packet.putInt(stub.size()).putShort((short) 0).putShort((short) 15).put(stub.toByteArray());
        return packet.array();
    }

    private static ByteBuffer rpcPacket(int type, int callId, int bodyLength) {
        ByteBuffer packet = ByteBuffer.allocate(16 + bodyLength).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte) 5).put((byte) 0).put((byte) type).put((byte) 3);
        packet.put((byte) 0x10).put((byte) 0).put((byte) 0).put((byte) 0);
        packet.putShort((short) packet.capacity()).putShort((short) 0).putInt(callId);
        return packet;
    }

    private static void requireBind(byte[] packet) throws IOException {
        ByteBuffer response = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        if (response.get() != 5 || response.get() != 0 || response.get() != 12
                || (response.get() & 0xff) != 3 || response.get() != 0x10
                || response.get() != 0 || response.get() != 0 || response.get() != 0
                || Short.toUnsignedInt(response.getShort()) != packet.length
                || Short.toUnsignedInt(response.getShort()) != 0 || response.getInt() != 1)
            throw new IOException("SMB server rejected the SRVSVC RPC bind");
        response.position(24);
        int addressLength = Short.toUnsignedInt(response.getShort());
        response.position((response.position() + addressLength + 3) & ~3);
        int results = response.get() & 0xff;
        response.position(response.position() + 2);
        if (results == 0 || Short.toUnsignedInt(response.getShort()) != 0)
            throw new IOException("SMB server does not support SRVSVC share enumeration");
    }

    private static byte[] responseStub(NamedPipe pipe, byte[] response, int callId) throws IOException {
        if (response == null || response.length == 0) throw new IOException("SMB server returned an empty SRVSVC RPC response");
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        byte[] current = response;
        boolean last = false;
        while (!last) {
            ByteBuffer input = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
            while (input.hasRemaining()) {
                int start = input.position();
                if (input.remaining() < 16 || input.get() != 5 || input.get() != 0)
                    throw new IOException("Invalid SMB SRVSVC RPC response");
                int type = input.get() & 0xff;
                int flags = input.get() & 0xff;
                input.position(start + 8);
                int fragmentLength = Short.toUnsignedInt(input.getShort());
                int authLength = Short.toUnsignedInt(input.getShort());
                int responseCallId = input.getInt();
                if (responseCallId != callId || fragmentLength < 24 || fragmentLength > current.length - start || authLength != 0)
                    throw new IOException("Invalid SMB SRVSVC RPC fragment");
                if (type == 3) throw new IOException("SMB SRVSVC RPC fault");
                if (type != 2) throw new IOException("Unexpected SMB SRVSVC RPC response type " + type);
                input.position(start + 20);
                if (Short.toUnsignedInt(input.getShort()) != 0) throw new IOException("Unexpected SMB SRVSVC RPC context");
                input.position(start + 24);
                int end = start + fragmentLength;
                stub.write(current, input.position(), end - input.position());
                input.position(end);
                last = (flags & 2) != 0;
                if (last && input.hasRemaining()) throw new IOException("Unexpected data after SMB SRVSVC RPC response");
            }
            if (!last) {
                current = new byte[65535];
                int count = pipe.read(current);
                if (count == 0) throw new IOException("Incomplete SMB SRVSVC RPC response");
                current = Arrays.copyOf(current, count);
            }
        }
        return stub.toByteArray();
    }

    private static SharePage decodePage(byte[] stub) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != 1 || input.getInt() != 1) throw new IOException("Invalid SMB share enumeration response");
        int container = input.getInt();
        int entries = 0;
        int array = 0;
        int count = 0;
        List<ShareInfo> rows = new ArrayList<>();
        if (container != 0) {
            entries = input.getInt();
            array = input.getInt();
            if (array != 0) {
                count = input.getInt();
                if (count < 0 || count != entries || count > input.remaining() / 12)
                    throw new IOException("Invalid SMB share enumeration entry count");
                for (int i = 0; i < count; i++) rows.add(new ShareInfo(input.getInt(), input.getInt(), input.getInt()));
            } else if (entries != 0) throw new IOException("SMB server omitted the share entries");
        }
        for (ShareInfo row : rows) {
            if (row.namePointer == 0) throw new IOException("SMB server returned a share without a name");
            String name = readString(input);
            if (row.remarkPointer != 0) readString(input);
            RemotePath.validateName(name);
            row.name = name;
        }
        input.getInt(); // TotalEntries
        int resumePointer = input.getInt();
        int resume = resumePointer == 0 ? 0 : input.getInt();
        int status = input.getInt();
        return new SharePage(rows, resume, status);
    }

    private static String readString(ByteBuffer input) throws IOException {
        int maximum = input.getInt();
        int offset = input.getInt();
        int count = input.getInt();
        if (offset != 0 || count < 0 || count > maximum || count > input.remaining() / 2)
            throw new IOException("Invalid SMB share name string");
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) value.append(input.getChar());
        if (value.length() > 0 && value.charAt(value.length() - 1) == '\0') value.setLength(value.length() - 1);
        input.position((input.position() + 3) & ~3);
        return value.toString();
    }

    private static void putInt(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    private static void putShort(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
    }

    private static final class ShareInfo {
        final int namePointer;
        final int type;
        final int remarkPointer;
        String name;

        ShareInfo(int namePointer, int type, int remarkPointer) {
            this.namePointer = namePointer;
            this.type = type;
            this.remarkPointer = remarkPointer;
        }
    }

    private static final class SharePage {
        final List<ShareInfo> shares;
        final int resume;
        final int status;

        SharePage(List<ShareInfo> shares, int resume, int status) {
            this.shares = shares;
            this.resume = resume;
            this.status = status;
        }
    }
}
