// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import io.github.gccammelli.pandy.studio.model.Mesh
import io.github.gccammelli.pandy.studio.model.Vec3

/** Reads and writes binary or ASCII STL files. */
object Stl {
    private data class Key(val x: Int, val y: Int, val z: Int)

    fun read(bytes: ByteArray): Mesh {
        require(bytes.size >= 5) { "STL input is too short" }
        if (bytes.size >= 84) {
            val count = u32(bytes, 80)
            if (count <= (Int.MAX_VALUE - 84L) / 50L && 84L + 50L * count == bytes.size.toLong()) return readBinary(bytes, count.toInt())
        }
        val text = bytes.decodeToString()
        require(text.trimStart().startsWith("solid", ignoreCase = true) && text.contains("facet", ignoreCase = true)) { "Malformed STL: expected a valid binary length or ASCII solid" }
        return readAscii(text)
    }

    private fun readBinary(bytes: ByteArray, count: Int): Mesh {
        val positions = ArrayList<Float>(count * 9); val indices = ArrayList<Int>(count * 3)
        val vertices = HashMap<Key, Int>(); var offset = 84
        fun add(x: Float, y: Float, z: Float) {
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "Malformed STL: non-finite vertex" }
            val key = Key(x.toRawBits(), y.toRawBits(), z.toRawBits())
            val index = vertices.getOrPut(key) { val i=positions.size/3;positions.add(x);positions.add(y);positions.add(z);i }
            indices.add(index)
        }
        repeat(count) {
            offset += 12
            repeat(3) { add(readFloat(bytes,offset),readFloat(bytes,offset+4),readFloat(bytes,offset+8)); offset += 12 }
            offset += 2
        }
        return Mesh(positions.toFloatArray(),indices.toIntArray())
    }

    private fun readAscii(text: String): Mesh {
        val tokens=text.split(Regex("\\s+"));val positions=ArrayList<Float>();val indices=ArrayList<Int>();val vertices=HashMap<Key,Int>();var found=0
        var i=0
        while(i<tokens.size) {
            if(tokens[i].equals("vertex",true)) {
                require(i+3<tokens.size) { "Malformed ASCII STL: incomplete vertex" }
                val x=tokens[i+1].toFloatOrNull();val y=tokens[i+2].toFloatOrNull();val z=tokens[i+3].toFloatOrNull()
                require(x!=null && y!=null && z!=null && x.isFinite() && y.isFinite() && z.isFinite()) { "Malformed ASCII STL: invalid vertex coordinates" }
                val key=Key(x.toRawBits(),y.toRawBits(),z.toRawBits());val index=vertices.getOrPut(key){val v=positions.size/3;positions.add(x);positions.add(y);positions.add(z);v};indices.add(index);found++;i+=4
            } else i++
        }
        require(found>0 && found%3==0) { "Malformed ASCII STL: expected complete triangular facets" }
        return Mesh(positions.toFloatArray(),indices.toIntArray())
    }

    fun writeBinary(mesh: Mesh, name: String = "Pandy Studio"): ByteArray {
        val out=ByteArray(84+mesh.triangleCount*50);val label=name.encodeToByteArray();label.copyInto(out,0,endIndex=minOf(label.size,80));putU32(out,80,mesh.triangleCount.toLong())
        var offset=84
        for(t in 0 until mesh.triangleCount) {
            val a=point(mesh,mesh.indices[t*3]);val b=point(mesh,mesh.indices[t*3+1]);val c=point(mesh,mesh.indices[t*3+2]);val n=(b-a).cross(c-a).normalized()
            writeVec(out,offset,n);offset+=12;writeVec(out,offset,a);offset+=12;writeVec(out,offset,b);offset+=12;writeVec(out,offset,c);offset+=12;out[offset++]=0;out[offset++]=0
        }
        return out
    }
    private fun point(mesh:Mesh,i:Int)=Vec3(mesh.positions[i*3],mesh.positions[i*3+1],mesh.positions[i*3+2])
    private fun writeVec(out:ByteArray,at:Int,v:Vec3){putU32(out,at,v.x.toRawBits().toLong() and 0xffffffffL);putU32(out,at+4,v.y.toRawBits().toLong() and 0xffffffffL);putU32(out,at+8,v.z.toRawBits().toLong() and 0xffffffffL)}
    private fun readFloat(b:ByteArray,at:Int)=Float.fromBits(u32(b,at).toInt())
    private fun u32(b:ByteArray,at:Int):Long=(b[at].toLong() and 255L) or ((b[at+1].toLong() and 255L) shl 8) or ((b[at+2].toLong() and 255L) shl 16) or ((b[at+3].toLong() and 255L) shl 24)
    private fun putU32(b:ByteArray,at:Int,v:Long){for(i in 0..3)b[at+i]=(v ushr (8*i)).toByte()}
}
