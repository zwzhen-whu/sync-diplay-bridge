package com.local.diplay.clusterbridge.audio;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Fake transport only: no ADB daemon, root process or socket is contacted. */
public final class NativeBootVolumeShellTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static byte[] packet(String command,int a,int b,String text)throws IOException{
        byte[] data=text.getBytes(StandardCharsets.US_ASCII);
        int code=ByteBuffer.wrap(command.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt(),sum=0;
        for(byte v:data)sum+=v&255;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(code).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~code).array());
        out.write(data);return out.toByteArray();
    }
    private static byte[] join(byte[]...pieces)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:pieces)out.write(p);return out.toByteArray();
    }
    private static byte[] ready()throws IOException{
        return join(packet("CNXN",0,4096,"device"),packet("OKAY",7,1,""),packet("WRTE",7,1,"READY\n"));
    }
    private static final class Fake extends Socket{
        final ByteArrayInputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();boolean closed;
        Fake(byte[] bytes){input=new ByteArrayInputStream(bytes);}
        public void connect(SocketAddress address,int timeout){
            InetSocketAddress target=(InetSocketAddress)address;
            check("127.0.0.1".equals(target.getAddress().getHostAddress())&&target.getPort()==5555,"loopback only");
        }
        public void setSoTimeout(int timeout){}public void setTcpNoDelay(boolean v){}
        public InputStream getInputStream(){return input;}public OutputStream getOutputStream(){return output;}
        public void close(){closed=true;}
        String sent(){return new String(output.toByteArray(),StandardCharsets.US_ASCII);}
    }
    public static void main(String[] args)throws Exception{
        for(boolean nativeVolume:new boolean[]{false,true}){
            Fake f=new Fake(join(ready(),packet("OKAY",7,1,""),packet("WRTE",7,1,"RESULT FIXED 1 3551\n")));
            MicPacketShell shell=new MicPacketShell("/data/app/a'b/base.apk",nativeVolume,f);
            check("RESULT FIXED 1 3551".equals(shell.exchange("F 1")),"validated stream response");
            check(f.sent().contains(nativeVolume?".NativeBootVolumeTool":".MicPacketRuntimeTool"),"fixed helper selected");
            check(!f.sent().contains(nativeVolume?".MicPacketRuntimeTool":".NativeBootVolumeTool"),"existing mic helper remains separate");
            check(f.sent().contains("'\\''"),"APK apostrophe is shell quoted");
            check(f.sent().indexOf("F 1\n")==f.sent().lastIndexOf("F 1\n"),"mutation sent once");shell.close();check(f.closed,"close releases transport");
        }
        Fake auth=new Fake(packet("AUTH",1,0,"challenge"));
        try{new MicPacketShell("/bridge.apk",true,auth);throw new AssertionError();}
        catch(IOException expected){check(auth.closed&&!auth.sent().contains("shell:"),"AUTH fails without sending a root command");}
        Fake f=new Fake(join(ready(),packet("WRTE",8,1,"RESULT OFF\n")));
        MicPacketShell shell=new MicPacketShell("/bridge.apk",true,f);
        try{shell.exchange("R");throw new AssertionError();}
        catch(IOException expected){check(!f.sent().contains("RESULT OFF"),"foreign stream never acknowledged");}shell.close();
        f=new Fake(ready());shell=new MicPacketShell("/bridge.apk",true,f);
        try{shell.exchange("F 1");throw new AssertionError();}
        catch(IOException expected){check(f.sent().indexOf("F 1\n")==f.sent().lastIndexOf("F 1\n"),"lost acknowledgement never retries mutation");}shell.close();
        byte[] corrupt=packet("CNXN",0,4096,"hello");corrupt[16]++;
        f=new Fake(corrupt);try{new MicPacketShell("/bridge.apk",true,f);throw new AssertionError();}
        catch(IOException expected){check(f.closed&&!f.sent().contains("shell:"),"checksum failure closes before shell launch");}
        System.out.println("NativeBootVolumeShellTest: "+checks+" checks passed (in-memory transport)");
    }
}
