package com.local.diplay.clusterbridge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** One fixed helper on this head unit's existing loopback ADB. No keys, pairing or remote hosts. */
final class LocalAdbShell implements AutoCloseable {
    private final Socket socket = new Socket();
    private DataInputStream input;
    private OutputStream output;
    private int remote;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private static int command(String text) { return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static final int CNXN = command("CNXN"), AUTH = command("AUTH"), OPEN = command("OPEN"), OKAY = command("OKAY"), WRTE = command("WRTE"), CLSE = command("CLSE");
    private static final class Packet { int command, arg0, arg1; byte[] data; }

    LocalAdbShell(String apk) throws IOException {
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555), 2000);
            socket.setSoTimeout(3000); socket.setTcpNoDelay(true);
            input = new DataInputStream(socket.getInputStream()); output = socket.getOutputStream();
            send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            Packet hello = read();
            if (hello.command == AUTH) throw new IOException("ADB authorization required");
            if (hello.command != CNXN) throw new IOException("ADB handshake failed");
            String shell = "su 0 sh -c " + quote("CLASSPATH=" + quote(apk) + " app_process /system/bin com.local.diplay.clusterbridge.CallDisplayTool");
            send(OPEN, 1, 0, ("shell:" + shell + "\0").getBytes(StandardCharsets.UTF_8));
            Packet opened = read();
            if (opened.command != OKAY || opened.arg1 != 1) throw new IOException("ADB shell unavailable");
            remote = opened.arg0;
            if (!"READY".equals(reply())) throw new IOException("Call display helper unavailable");
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    String exchange(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 2048) throw new IOException("Call command too long");
        send(WRTE, 1, remote, bytes);
        boolean acknowledged = false;
        for (int i = 0; i < 20 && !acknowledged; i++) {
            Packet packet = read();
            if (packet.command == OKAY && packet.arg0 == remote && packet.arg1 == 1) acknowledged = true;
            else consume(packet);
        }
        if (!acknowledged) throw new IOException("ADB write acknowledgement missing");
        return reply();
    }
    private String reply() throws IOException {
        for (int i = 0; i < 24; i++) {
            while (!lines.isEmpty()) {
                String line = lines.removeFirst().trim();
                if (line.equals("READY") || line.equals("OK") || line.equals("HANDOFF") || line.startsWith("ERR ")) return line;
            }
            consume(read());
        }
        throw new IOException("Call helper response missing");
    }
    private void consume(Packet packet) throws IOException {
        if (packet.arg0 != remote || packet.arg1 != 1) throw new IOException("ADB stream mismatch");
        if (packet.command == CLSE) throw new IOException("Call helper stopped");
        if (packet.command != WRTE) throw new IOException("Unexpected ADB packet");
        for (byte b : packet.data) {
            if (b == '\n') { lines.add(new String(partial.toByteArray(), StandardCharsets.US_ASCII)); partial.reset(); }
            else { if (partial.size() >= 4096 || lines.size() >= 32) throw new IOException("Call helper output too long"); partial.write(b); }
        }
        send(OKAY, 1, remote, new byte[0]);
    }
    private Packet read() throws IOException {
        byte[] header = new byte[24]; input.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Packet packet = new Packet(); packet.command = b.getInt(); packet.arg0 = b.getInt(); packet.arg1 = b.getInt();
        int size = b.getInt(), checksum = b.getInt(), magic = b.getInt();
        if (magic != (packet.command ^ -1) || size < 0 || size > 65536) throw new IOException("Invalid ADB packet");
        packet.data = new byte[size]; input.readFully(packet.data); int sum = 0; for (byte value : packet.data) sum += value & 255;
        if (sum != checksum) throw new IOException("ADB checksum mismatch");
        return packet;
    }
    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0; for (byte value : data) checksum += value & 255;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(command ^ -1);
        output.write(header.array()); output.write(data); output.flush();
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    public void close() { try { socket.close(); } catch (IOException ignored) { } }
}
