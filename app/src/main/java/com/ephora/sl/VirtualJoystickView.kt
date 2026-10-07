package com.ephora.sl
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
class VirtualJoystickView @JvmOverloads constructor(c:Context,a:AttributeSet?=null):View(c,a){
 private val ring=Paint(1);private val knob=Paint(1);private var x=0f;private var y=0f
 init{isClickable=false;ring.style=Paint.Style.STROKE;ring.strokeWidth=4f;ring.color=0x66FFFFFF;knob.color=0x99FFFFFF.toInt()}
 fun setState(a:Float,b:Float){x=a;y=b;postInvalidateOnAnimation()}
 override fun onDraw(c:Canvas){val cx=width*.5f;val cy=height*.5f;val r=minOf(width,height)*.38f;c.drawCircle(cx,cy,r,ring);c.drawCircle(cx+x*r*.72f,cy+y*r*.72f,r*.28f,knob)}
 override fun onTouchEvent(e:MotionEvent)=false
}