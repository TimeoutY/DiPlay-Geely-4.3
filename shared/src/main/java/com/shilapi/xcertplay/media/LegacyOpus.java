package com.shilapi.xcertplay.media;
import java.util.WeakHashMap;
import io.github.jaredmdobson.concentus.OpusDecoder;
public final class LegacyOpus {
 private static final class State { final OpusDecoder decoder; final short[] pcm; int errors; State(int rate,int channels)throws Exception{decoder=new OpusDecoder(rate,channels);pcm=new short[5760*channels];} }
 private static final WeakHashMap<Object,State> decoders=new WeakHashMap<Object,State>();
 public static synchronized byte[] decode(Object owner,byte[] packet,int rate,int channels) throws Exception {
  State d=decoders.get(owner);
  if(d==null){d=new State(rate,channels);decoders.put(owner,d);android.util.Log.i("xcertplay-usb","P21 software Opus initialized rate="+rate+" channels="+channels);}
  short[] pcm=d.pcm;int n;
  try {n=d.decoder.decode(packet,0,packet.length,pcm,0,5760,false);}catch(Exception e){if(d.errors++<3)android.util.Log.w("xcertplay-usb","P21 Opus packet rejected",e);return new byte[0];}
  byte[] out=new byte[n*channels*2];for(int i=0;i<n*channels;i++){out[2*i]=(byte)pcm[i];out[2*i+1]=(byte)(pcm[i]>>>8);}return out;
 }
 public static synchronized void release(Object owner){decoders.remove(owner);}
 public static boolean hudSupported(android.content.Context c){try{android.content.pm.ServiceInfo s=c.getPackageManager().getServiceInfo(new android.content.ComponentName("com.ts.car.someip.service","com.ts.car.someip.service.manager.SomeIpServerService"),0);return s.enabled&&s.applicationInfo.enabled;}catch(android.content.pm.PackageManager.NameNotFoundException e){return false;}}
}