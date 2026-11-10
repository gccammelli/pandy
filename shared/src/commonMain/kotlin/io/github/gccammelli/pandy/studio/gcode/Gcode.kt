// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.gcode

import kotlin.math.*

/** Bambu/Marlin print feature categories in preview order. */
enum class Feature { OUTER_WALL, INNER_WALL, OVERHANG_WALL, SPARSE_INFILL, INTERNAL_SOLID_INFILL, TOP_SURFACE, BOTTOM_SURFACE, BRIDGE, GAP_FILL, SKIRT, BRIM, SUPPORT, SUPPORT_INTERFACE, SUPPORT_TRANSITION, PRIME_TOWER, IRONING, CUSTOM, TRAVEL, OTHER }

/** A layer's line segments and the feature, width, and tool associated with each segment. */
class Layer(val z: Float, val height: Float, val segments: FloatArray, val features: ByteArray, val widths: FloatArray, val tools: ByteArray)

/** Header estimates and material statistics, when supplied by the slicer. */
data class Stats(val estimatedSeconds: Int?, val filamentLengthMm: Float?, val filamentGrams: Float?, val layerCount: Int?)

/** Parsed preview geometry with print metadata. */
class GcodeModel(val layers: List<Layer>, val stats: Stats)

/** Single-pass parser for Bambu Studio plate G-code previews. */
object GcodeParser {
    private class Floats(initial:Int=128) {
        var data=FloatArray(initial);var size=0
        fun add(v:Float){if(size==data.size)data=data.copyOf(max(16,data.size*2));data[size++]=v}
        fun result()=data.copyOf(size)
    }
    private class Bytes(initial:Int=64) {
        var data=ByteArray(initial);var size=0
        fun add(v:Byte){if(size==data.size)data=data.copyOf(max(16,data.size*2));data[size++]=v}
        fun result()=data.copyOf(size)
    }
    private class BuildingLayer(var z:Float=0f,var height:Float=0f) {
        val segments=Floats();val features=Bytes();val widths=Floats();val tools=Bytes()
        fun finish()=Layer(z,height,segments.result(),features.result(),widths.result(),tools.result())
        fun append(a:FloatArray,b:FloatArray,feature:Feature,width:Float,tool:Int){for(v in a)segments.add(v);for(v in b)segments.add(v);features.add(feature.ordinal.toByte());widths.add(width);tools.add(tool.toByte())}
        val count get()=features.size
    }

    fun parse(text:CharSequence,includeTravel:Boolean=false):GcodeModel {
        var x=0f;var y=0f;var z=0f;var e=0f;var xyzAbsolute=true;var eAbsolute=true;var tool=0;var width=0f
        var feature=Feature.OTHER;var current=BuildingLayer();val layers=ArrayList<BuildingLayer>();layers.add(current)
        var explicitLayers=false;var currentLayerZ:Float?=null;var pendingChange=false
        var estimated:Int?=null;var filamentLength:Float?=null;var filamentGrams:Float?=null;var layerCount:Int?=null
        var lineStart=0
        while(lineStart<text.length) {
            var end=lineStart;while(end<text.length&&text[end]!='\n'&&text[end]!='\r')end++
            var comment=end;for(i in lineStart until end)if(text[i]==';'){comment=i;break}
            if(comment<end) {
                val raw=text.subSequence(comment+1,end).toString().trim();val low=raw.lowercase()
                when {
                    low=="change_layer"->{ explicitLayers=true;pendingChange=true;current=BuildingLayer();layers.add(current);currentLayerZ=null }
                    low.startsWith("z_height:")->raw.substringAfter(':').trim().toFloatOrNull()?.let { current.z=it;currentLayerZ=it;pendingChange=false }
                    low.startsWith("layer_height:")->raw.substringAfter(':').trim().toFloatOrNull()?.let { current.height=it }
                    low.startsWith("line_width:")->raw.substringAfter(':').trim().toFloatOrNull()?.takeIf { it>=0f }?.let { width=it }
                    low.startsWith("feature:")->feature=featureFrom(raw.substringAfter(':').trim())
                    // Bambu writes "; model printing time: 1h 33m 18s; total estimated time: 1h 41m 21s" on one line, or
                    // "; estimated printing time (normal mode) = 1h 37m 48s".
                    low.contains("total estimated time:")->estimated=parseDuration(raw.substring(low.indexOf("total estimated time:")+21).substringBefore(';'))
                    low.startsWith("estimated printing time")->if(estimated==null)estimated=parseDuration(raw.substringAfter('=').substringBefore(';'))
                    low.startsWith("model printing time:")->if(estimated==null)estimated=parseDuration(raw.substringAfter(':').substringBefore(';'))
                    low.startsWith("total filament length [mm]")->filamentLength=valueAfterColon(raw)
                    low.startsWith("total filament weight [g]")->filamentGrams=valueAfterColon(raw)
                    low.startsWith("total layer number:")->layerCount=valueAfterColon(raw)?.toInt()
                }
            }
            val stop=comment;var i=lineStart;var command='\u0000';var code=Float.NaN
            var hasX=false;var hasY=false;var hasZ=false;var hasE=false;var hasI=false;var hasJ=false
            var vx=0f;var vy=0f;var vz=0f;var ve=0f;var vi=0f;var vj=0f
            while(i<stop) {
                while(i<stop&&(text[i].isWhitespace()||text[i]=='*'))i++
                if(i>=stop)break
                val letter=text[i].uppercaseChar()
                if(!letter.isLetter()){i++;continue};i++
                while(i<stop&&text[i].isWhitespace())i++
                val parsed=readNumber(text,i,stop);i=parsed.second
                if(parsed.first==null)continue
                val n=parsed.first!!
                when(letter) {
                    'G','M'->{if(letter=='G'){command='G';code=n}else if(code.isNaN()){command='M';code=n}}
                    'T'->{if(n>=0f&&n<100f&&n.toInt().toFloat()==n)tool=n.toInt()}
                    'X'->{hasX=true;vx=n};'Y'->{hasY=true;vy=n};'Z'->{hasZ=true;vz=n};'E'->{hasE=true;ve=n};'I'->{hasI=true;vi=n};'J'->{hasJ=true;vj=n}
                }
            }
            if(!code.isNaN()) {
                if(command=='G'&&code==90f)xyzAbsolute=true
                else if(command=='G'&&code==91f)xyzAbsolute=false
                else if(command=='M'&&code==82f)eAbsolute=true
                else if(command=='M'&&code==83f)eAbsolute=false
                else if(command=='G'&&code==92f){if(hasE)e=ve}
                else if(command=='G'&&(code==0f||code==1f||code==2f||code==3f)) {
                    val nx=if(hasX){if(xyzAbsolute)vx else x+vx}else x
                    val ny=if(hasY){if(xyzAbsolute)vy else y+vy}else y
                    val nz=if(hasZ){if(xyzAbsolute)vz else z+vz}else z
                    val ne=if(hasE)ve else e
                    val extrusion=code!=0f&&hasE&&(if(eAbsolute)ne>e else ne>0f)
                    val selected=if(extrusion)feature else if(includeTravel)Feature.TRAVEL else null
                    if(hasZ&&pendingChange){current.z=nz;currentLayerZ=nz;pendingChange=false}
                    else if(hasZ&&!explicitLayers&&currentLayerZ!=null&&nz>currentLayerZ!!+0.0001f) { if(current.count>0){current=BuildingLayer();layers.add(current)};current.z=nz;currentLayerZ=nz }
                    else if(hasZ&&currentLayerZ==null){current.z=nz;currentLayerZ=nz}
                    if(selected!=null) {
                        val start=floatArrayOf(x,y,z);val finish=floatArrayOf(nx,ny,nz)
                        if(code==2f||code==3f) {
                            if(hasI||hasJ)appendArc(current,start,finish,if(hasI)vi else 0f,if(hasJ)vj else 0f,code==2f,selected,width,tool)
                            else current.append(start,finish,selected,width,tool)
                        } else if(x!=nx||y!=ny||z!=nz) current.append(start,finish,selected,width,tool)
                    }
                    x=nx;y=ny;z=nz;if(hasE)e=ne
                }
            }
            lineStart=end
            while(lineStart<text.length&&(text[lineStart]=='\r'||text[lineStart]=='\n'))lineStart++
        }
        val completed=layers.filter { it.count>0 }.map { it.finish() }
        return GcodeModel(completed,Stats(estimated,filamentLength,filamentGrams,layerCount))
    }

    private fun featureFrom(value:String):Feature=when(value.trim().lowercase()) {
        "outer wall"->Feature.OUTER_WALL;"inner wall"->Feature.INNER_WALL;"overhang wall"->Feature.OVERHANG_WALL;"sparse infill"->Feature.SPARSE_INFILL;"internal solid infill"->Feature.INTERNAL_SOLID_INFILL;"top surface"->Feature.TOP_SURFACE;"bottom surface"->Feature.BOTTOM_SURFACE;"bridge","internal bridge"->Feature.BRIDGE;"gap infill"->Feature.GAP_FILL;"skirt"->Feature.SKIRT;"brim"->Feature.BRIM;"support"->Feature.SUPPORT;"support interface"->Feature.SUPPORT_INTERFACE;"support transition"->Feature.SUPPORT_TRANSITION;"prime tower"->Feature.PRIME_TOWER;"ironing"->Feature.IRONING;"custom"->Feature.CUSTOM;"travel"->Feature.TRAVEL;else->Feature.OTHER
    }

    private fun appendArc(layer:BuildingLayer,start:FloatArray,end:FloatArray,oi:Float,oj:Float,clockwise:Boolean,feature:Feature,width:Float,tool:Int) {
        val cx=start[0]+oi;val cy=start[1]+oj;val dx=start[0]-cx;val dy=start[1]-cy;val radius=sqrt(dx*dx+dy*dy)
        if(radius<=0f){layer.append(start,end,feature,width,tool);return}
        val a0=atan2(dy,dx);val a1=atan2(end[1]-cy,end[0]-cx)
        var sweep=a1-a0
        if(clockwise){while(sweep>=0f)sweep-=2f*PI.toFloat()}else{while(sweep<=0f)sweep+=2f*PI.toFloat()}
        if(abs(end[0]-start[0])<1e-6f&&abs(end[1]-start[1])<1e-6f)sweep=if(clockwise)-2f*PI.toFloat() else 2f*PI.toFloat()
        val chordAngle=if(radius<=0.25f)PI.toFloat() else 2f*asin((0.25f/radius).coerceIn(-1f,1f))
        val maxAngle=min(5f*PI.toFloat()/180f,chordAngle).coerceAtLeast(0.0001f)
        var steps=ceil(abs(sweep)/maxAngle).toInt().coerceAtLeast(1)
        val dz=abs(end[2]-start[2])
        while (sqrt((2f*radius*sin(abs(sweep)/(2f*steps))).pow(2)+(dz/steps).pow(2))>0.5f) steps++
        var previous=start
        for(step in 1..steps) {
            val q=step.toFloat()/steps;val angle=a0+sweep*q
            val point=if(step==steps)end else floatArrayOf(cx+radius*cos(angle),cy+radius*sin(angle),start[2]+(end[2]-start[2])*q)
            layer.append(previous,point,feature,width,tool);previous=point
        }
    }

    private fun readNumber(s:CharSequence,begin:Int,end:Int):Pair<Float?,Int> {
        var i=begin;var sign=1.0
        if(i<end&&(s[i]=='+'||s[i]=='-')){if(s[i]=='-')sign=-1.0;i++}
        var value=0.0;var digits=0
        while(i<end&&s[i].isDigit()){value=value*10+(s[i].code-48);i++;digits++}
        if(i<end&&s[i]=='.'){i++;var place=0.1;while(i<end&&s[i].isDigit()){value+=(s[i].code-48)*place;place*=0.1;i++;digits++}}
        if(digits==0)return null to begin
        if(i<end&&(s[i]=='e'||s[i]=='E')) {
            var j=i+1;var expSign=1
            if(j<end&&(s[j]=='+'||s[j]=='-')){if(s[j]=='-')expSign=-1;j++}
            val ds=j;var exponent=0
            while(j<end&&s[j].isDigit()){exponent=exponent*10+(s[j].code-48);j++}
            if(j>ds){value*=10.0.pow(exponent*expSign);i=j}
        }
        return (value*sign).toFloat() to i
    }

    private fun valueAfterColon(s:String):Float? { val i=s.indexOf(':');return if(i<0)null else s.substring(i+1).trim().substringBefore(' ').toFloatOrNull() }
    private fun parseDuration(s:String):Int? {
        var total=0.0;var seen=false;var i=0
        while(i<s.length) {
            while(i<s.length&&!s[i].isDigit())i++
            if(i>=s.length)break
            var n=0;while(i<s.length&&s[i].isDigit()){n=n*10+(s[i].code-48);i++}
            while(i<s.length&&s[i].isWhitespace())i++
            if(i<s.length)when(s[i].lowercaseChar()){'d'->{total+=n*86400.0;seen=true;i++};'h'->{total+=n*3600.0;seen=true;i++};'m'->{total+=n*60.0;seen=true;i++};'s'->{total+=n;seen=true;i++};else->Unit}
        }
        return if(seen)total.toInt() else null
    }
}
