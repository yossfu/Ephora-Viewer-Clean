package com.ephora.sl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
object AvatarAppearanceStore{
 data class Appearance(val agentId:String,val visual:ByteArray,val textures:List<String>)
 private val map=LinkedHashMap<String,Appearance>()
 @Synchronized fun put(a:Appearance){map[a.agentId.lowercase(Locale.US)]=a}
 @Synchronized fun get(id:String?):Appearance?=id?.let{map[it.lowercase(Locale.US)]}
 @Synchronized fun clear(){map.clear()}
 fun accept(p:ByteArray):String?{try{if(p.size<19)return null;val id=uuid(p,0)?:return null;var o=17;val n=u16(p,o);o+=2;if(o+n>p.size)return null;val te=p.copyOfRange(o,o+n);o+=n;if(o>=p.size)return null;val vc=p[o].toInt()and 255;o++;if(o+vc>p.size)return null;val v=p.copyOfRange(o,o+vc);put(Appearance(id,v,textureIds(te)));return "AVATAR-APPEARANCE id8="+id.substring(0,8)+" params="+vc}catch(_:Throwable){return null}}
 private fun textureIds(raw:ByteArray):List<String>{try{var o=0;fun field(size:Int):Array<ByteArray>{val base=raw.copyOfRange(o,o+size);o+=size;val a=Array(45){base};while(o<raw.size){var m=0L;var more:Boolean;do{if(o>=raw.size)return a;val x=raw[o++].toInt()and 255;m=(m shl 7)or(x and 127).toLong();more=(x and 128)!=0}while(more);if(m==0L)return a;if(o+size>raw.size)return a;val v=raw.copyOfRange(o,o+size);o+=size;for(i in a.indices)if((m and(1L shl i))!=0L)a[i]=v};return a};return field(16).map{b->if(b.size<16)"" else{val h=b.joinToString(""){String.format("%02x",it.toInt()and 255)};h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20,32)}}}catch(_:Throwable){return emptyList()}}
 private fun uuid(a:ByteArray,o:Int):String?=try{val h=a.copyOfRange(o,o+16).joinToString(""){String.format("%02x",it.toInt()and 255)};h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20,32)}catch(_:Throwable){null}
 private fun u16(a:ByteArray,o:Int)=ByteBuffer.wrap(a,o,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()and 65535
}