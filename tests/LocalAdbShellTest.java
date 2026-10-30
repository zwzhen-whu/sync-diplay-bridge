package com.local.diplay.clusterbridge;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Entirely in memory: no device, socket connection or phone command is executed. */
public final class LocalAdbShellTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static byte[] packet(String command, int a, int b, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        int code = ByteBuffer.wrap(command.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt(), sum = 0;
        for (byte value : data) sum += value & 255;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(code).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~code).array());
        out.write(data); return out.toByteArray();
    }
    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] part : parts) out.write(part); return out.toByteArray();
    }
    private static byte[] ready() throws IOException {
        return join(packet("CNXN", 0, 4096, "device::\0"), packet("OKAY", 7, 1, ""), packet("WRTE", 7, 1, "READY\n"));
    }
    private static final class FakeSocket extends Socket {
        final InputStream in; final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int timeout; boolean closed; SocketAddress endpoint;
        FakeSocket(byte[] bytes) { in = new ByteArrayInputStream(bytes); }
        public void connect(SocketAddress address, int wait) { endpoint = address; check(wait == 2000, "bounded connect"); }
        public void setSoTimeout(int value) { timeout = value; }
        public void setTcpNoDelay(boolean value) { }
        public InputStream getInputStream() { return in; }
        public OutputStream getOutputStream() { return out; }
        public void close() { closed = true; }
        String output() { return new String(out.toByteArray(), StandardCharsets.US_ASCII); }
    }
    private static void refused(byte[] bytes, String why) throws Exception {
        FakeSocket socket = new FakeSocket(bytes);
        try { new LocalAdbShell("/data/app/bridge.apk", true, socket); throw new AssertionError(why); }
        catch (IOException expected) { check(socket.closed, why + " closes socket"); }
    }
    public static void main(String[] args) throws Exception {
        for (boolean keys : new boolean[]{false, true}) {
            // A reply can arrive before the write acknowledgement; it must not be lost.
            FakeSocket socket = new FakeSocket(join(ready(), packet("WRTE", 7, 1, keys ? "RESULT END\n" : "OK\n"), packet("OKAY", 7, 1, "")));
            LocalAdbShell shell = new LocalAdbShell("/data/app/bridge's.apk", keys, socket);
            check(socket.timeout == (keys ? 4500 : 3000), "bounded helper-specific read wait");
            InetSocketAddress endpoint = (InetSocketAddress)socket.endpoint;
            check(endpoint.getAddress().isLoopbackAddress() && endpoint.getPort() == 5555, "fixed local endpoint");
            check(shell.exchange("P").equals(keys ? "RESULT END" : "OK"), "response before ack preserved");
            String output = socket.output();
            check(output.contains(keys ? ".KeyControlTool" : ".CallDisplayTool"), "fixed helper selection");
            check(!output.contains(keys ? ".CallDisplayTool" : ".KeyControlTool"), "one helper only");
            check(output.contains("su 0 sh -c") && output.contains("'\\''"), "fixed command and escaped APK path");
            check(output.indexOf("P\n") >= 0 && output.indexOf("P\n") == output.lastIndexOf("P\n"), "one heartbeat write without automatic repeat");
            shell.close(); check(socket.closed, "normal close");
        }
        FakeSocket auth = new FakeSocket(packet("AUTH", 1, 0, "challenge"));
        try { new LocalAdbShell("/data/app/bridge.apk", true, auth); throw new AssertionError("AUTH must fail"); }
        catch (IOException expected) { check(auth.closed && !auth.output().contains("shell:"), "authorization required: no shell or keys sent"); }
        refused(packet("WRTE", 7, 1, "READY\n"), "unexpected handshake");
        byte[] corrupt = packet("CNXN", 0, 4096, "device"); corrupt[16]++;
        refused(corrupt, "checksum failure");
        refused(join(packet("CNXN", 0, 4096, ""), packet("OKAY", 7, 2, "")), "wrong shell stream");
        FakeSocket wrong = new FakeSocket(join(ready(), packet("WRTE", 8, 1, "RESULT ANSWER\n")));
        LocalAdbShell shell = new LocalAdbShell("/data/app/bridge.apk", true, wrong);
        try { shell.exchange("K\trequest"); throw new AssertionError("foreign stream accepted"); }
        catch (IOException expected) { check(!wrong.output().contains("RESULT ANSWER"), "foreign stream never acknowledged as result"); }
        shell.close();
        FakeSocket eof = new FakeSocket(ready()); shell = new LocalAdbShell("/data/app/bridge.apk", true, eof);
        try { shell.exchange("K\trequest"); throw new AssertionError("missing ack accepted"); }
        catch (IOException expected) {
            String sent = eof.output(); check(sent.indexOf("K\trequest") == sent.lastIndexOf("K\trequest"), "uncertain request written once");
        }
        shell.close();
        System.out.println("LocalAdbShellTest: " + checks + " checks passed");
    }
}
