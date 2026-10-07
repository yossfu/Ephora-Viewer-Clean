package com.ephora.sl
import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
object AvatarMeshAssets{
 data class Part(val name:String,val vertices:FloatArray,val count:Int,val textureIndex:Int)
 private val cache=HashMap<String,Part>();private var loaded=false
 @Synchronized fun init(c:Context){if(loaded)return;loaded=true;try{load(c,"avatar_head.llm","head",8);load(c,"avatar_upper_body.llm","upper",9);load(c,"avatar_lower_body.llm","lower",10);load(c,"avatar_eye.llm","eyes",11)}catch(_:Throwable){}}
 fun parts(c:Context,v:ByteArray):List<Part>{init(c);return cache.values.toList()}
 private fun load(c:Context,file:String,name:String,ti:Int){val i=DataInputStream(BufferedInputStream(c.assets.open("avatar/"+file)));val h=ByteArray(24);i.readFully(h);if(String(h,Charsets.US_ASCII)!="Linden Binary Mesh 1.0")throw IllegalArgumentException("llm");val hw=i.readUnsignedByte()!=0;val hd=i.readUnsignedByte()!=0;repeat(3){rf(i)};repeat(3){rf(i)};i.readUnsignedByte();repeat(3){rf(i)};val n=i.readUnsignedShort();val xyz=FloatArray(n*3){rf(i)};val no=FloatArray(n*3){rf(i)};repeat(n*3){rf(i)};val uv=FloatArray(n*2){rf(i)};if(hd)repeat(n*2){rf(i)};if(hw)repeat(n){rf(i)};val nf=i.readUnsignedShort();val f=IntArray(nf*3){i.readUnsignedShort()};val out=FloatArray(f.size*8);var p=0;for(id in f){val k=id*3;val u=id*2;out[p++]=xyz[k];out[p++]=xyz[k+2];out[p++]=-xyz[k+1];out[p++]=no[k];out[p++]=no[k+2];out[p++]=-no[k+1];out[p++]=uv[u];out[p++]=uv[u+1]};i.close();cache[name]=Part(name,out,f.size,ti)}
 private fun rf(i:DataInputStream)=ByteBuffer.wrap(ByteArray(4).also{i.readFully(it)}).order(ByteOrder.LITTLE_ENDIAN).float
}