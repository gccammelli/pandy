// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Mesh
import io.github.gccammelli.pandy.studio.model.Vec3
import kotlin.math.floor

/** A named mesh placement in a 3MF scene. */
data class SceneObject(val name: String, val mesh: Mesh, val transform: Mat4)

/** Reads common 3MF package layouts and writes a deterministic plain project. */
object ThreeMf {
    private data class Node(val name: String, val attrs: Map<String,String>, val children: MutableList<Node> = mutableListOf())
    private data class Component(val objectId: String, val path: String?, val transform: Mat4)
    private data class Model(val unit: Float, val objects: Map<String,Node>, val root: Node)

    fun read(bytes: ByteArray): List<SceneObject> {
        val entries=try { Zip.read(bytes) } catch(e: Exception) { throw IllegalArgumentException("Invalid 3MF ZIP: ${e.message}",e) }
        val primary=entries["3D/3dmodel.model"] ?: entries.entries.firstOrNull { it.key.endsWith(".model") }?.value
            ?: throw IllegalArgumentException("3MF archive has no model document")
        val docs=HashMap<String,Model>()
        fun document(path:String, data:ByteArray):Model = docs.getOrPut(path) { parseModel(data.decodeToString()) }
        val rootModel=document("3D/3dmodel.model",primary)
        val result=ArrayList<SceneObject>();val builds=rootModel.root.child("build")?.children.orEmpty().filter { it.name.local()=="item" }
        var number=1
        for(item in builds) {
            val objectId=item.attrs["objectid"] ?: throw IllegalArgumentException("3MF build item has no objectid")
            val node=rootModel.objects[objectId] ?: throw IllegalArgumentException("3MF object $objectId was not found")
            val mesh=resolveMesh(node,rootModel,entries,docs,HashSet())
            val transform=parseTransform(item.attrs["transform"])
            val scaled=scaleMesh(mesh,rootModel.unit)
            val mmTransform=Mat4.translation(transform.m[12]*rootModel.unit,transform.m[13]*rootModel.unit,transform.m[14]*rootModel.unit) * Mat4(floatArrayOf(transform.m[0],transform.m[1],transform.m[2],0f,transform.m[4],transform.m[5],transform.m[6],0f,transform.m[8],transform.m[9],transform.m[10],0f,0f,0f,0f,1f))
            result.add(SceneObject(node.attrs["name"] ?: "Object $number",scaled,mmTransform));number++
        }
        return result
    }

    private fun resolveMesh(node:Node, model:Model, entries:Map<String,ByteArray>, docs:MutableMap<String,Model>, visiting:MutableSet<Node>):Mesh {
        require(visiting.add(node)) { "Cyclic 3MF object components" }
        var mesh=node.child("mesh")?.let { parseMesh(it) } ?: Mesh.empty
        val components=node.child("components")?.children.orEmpty().filter { it.name.local()=="component" }
        for(c in components) {
            val id=c.attrs["objectid"] ?: throw IllegalArgumentException("3MF component has no objectid")
            val path=c.attrs.entries.firstOrNull { it.key.substringAfter(':')=="path" }?.value
            val refModel=if(path==null) model else {
                val normalized=normalizePath(path)
                val data=entries[normalized] ?: throw IllegalArgumentException("3MF component model not found: $path")
                docs.getOrPut(normalized) { parseModel(data.decodeToString()) }
            }
            val ref=refModel.objects[id] ?: throw IllegalArgumentException("3MF component object $id was not found")
            val childMesh=resolveMesh(ref,refModel,entries,docs,visiting).let { scaleMesh(it,refModel.unit/model.unit) }
            val transform=parseTransform(c.attrs["transform"])
            mesh=mesh.merged(childMesh.transformed(transform))
        }
        visiting.remove(node)
        return mesh
    }

    private fun parseMesh(meshNode:Node):Mesh {
        val vertices=meshNode.child("vertices")?.children.orEmpty().filter { it.name.local()=="vertex" }
        val positions=FloatArray(vertices.size*3)
        for(i in vertices.indices) { positions[i*3]=number(vertices[i],"x");positions[i*3+1]=number(vertices[i],"y");positions[i*3+2]=number(vertices[i],"z") }
        val tris=meshNode.child("triangles")?.children.orEmpty().filter { it.name.local()=="triangle" }
        val indices=IntArray(tris.size*3)
        for(i in tris.indices) for(j in 0..2) { val v=tris[i].attrs["v${j+1}"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid 3MF triangle index");require(v in vertices.indices) { "3MF triangle index out of range" };indices[i*3+j]=v }
        return Mesh(positions,indices)
    }

    private fun parseModel(xml:String):Model {
        val root=parseXml(xml);require(root.name.local()=="model") { "3MF model document has no model element" }
        val unit=when(root.attrs["unit"]?.lowercase() ?: "millimeter") { "millimeter"->1f;"inch"->25.4f;"micron"->0.001f;"centimeter"->10f;"meter"->1000f;"foot"->304.8f;else->throw IllegalArgumentException("Unsupported 3MF unit: ${root.attrs["unit"]}") }
        val objects=root.child("resources")?.children.orEmpty().filter { it.name.local()=="object" }
        val byId=LinkedHashMap<String,Node>()
        for(obj in objects) { val id=obj.attrs["id"] ?: throw IllegalArgumentException("3MF object has no id");byId[id]=obj }
        return Model(unit,byId,root)
    }

    private fun parseTransform(value:String?):Mat4 = if(value.isNullOrBlank()) Mat4.identity() else Mat4.fromThreeMf(value)
    private fun scaleMesh(mesh:Mesh,scale:Float)=if(scale==1f)mesh else Mesh(FloatArray(mesh.positions.size){mesh.positions[it]*scale},mesh.indices.copyOf())
    private fun number(node:Node,key:String):Float=node.attrs[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid 3MF vertex $key coordinate")
    private fun Node.child(local:String)=children.firstOrNull { it.name.local()==local }
    private fun String.local()=substringAfterLast(':')
    private fun normalizePath(path:String):String { val parts=ArrayList<String>();for(p in path.replace('\\','/').split('/')) when(p){"","."->Unit;".."->{if(parts.isNotEmpty())parts.removeAt(parts.lastIndex)};else->parts.add(p)};return parts.joinToString("/") }

    private fun parseXml(xml:String):Node {
        val root=Node("#root",emptyMap());val stack=ArrayList<Node>();stack.add(root);var i=0
        while(i<xml.length) {
            if(xml[i]!='<') { i++;continue }
            if(xml.startsWith("<!--",i)) { val end=xml.indexOf("-->",i+4);require(end>=0) { "Malformed XML comment" };i=end+3;continue }
            if(xml.startsWith("<![CDATA[",i)) { val end=xml.indexOf("]]>",i+9);require(end>=0) { "Malformed XML CDATA" };i=end+3;continue }
            if(xml.startsWith("<?",i) || xml.startsWith("<!",i)) { i=skipTagDeclaration(xml,i);continue }
            i++
            val closing=i<xml.length && xml[i]=='/'
            if(closing)i++
            while(i<xml.length && xml[i].isWhitespace())i++
            val start=i;while(i<xml.length && isNameChar(xml[i]))i++
            require(i>start) { "Malformed XML tag" };val name=xml.substring(start,i)
            if(closing) { while(i<xml.length && xml[i]!='>')i++;require(i<xml.length) { "Unterminated XML closing tag" };if(stack.size>1)stack.removeAt(stack.lastIndex);i++;continue }
            val attrs=LinkedHashMap<String,String>();var self=false
            while(i<xml.length) {
                while(i<xml.length && (xml[i].isWhitespace() || xml[i]=='/')) { if(xml[i]=='/')self=true;i++ }
                if(i>=xml.length)throw IllegalArgumentException("Unterminated XML start tag")
                if(xml[i]=='>') { i++;break }
                val a=i;while(i<xml.length && isNameChar(xml[i]))i++;require(i>a) { "Malformed XML attribute" };val key=xml.substring(a,i)
                while(i<xml.length && xml[i].isWhitespace())i++;require(i<xml.length && xml[i]=='=') { "XML attribute lacks value" };i++;while(i<xml.length&&xml[i].isWhitespace())i++
                require(i<xml.length && (xml[i]=='\''||xml[i]=='\"')) { "XML attribute value must be quoted" };val quote=xml[i++];val v=i;while(i<xml.length&&xml[i]!=quote)i++;require(i<xml.length) { "Unterminated XML attribute" };attrs[key]=decodeEntities(xml.substring(v,i));i++
            }
            val node=Node(name,attrs);stack.last().children.add(node);if(!self)stack.add(node)
        }
        return root.children.firstOrNull() ?: throw IllegalArgumentException("Empty XML document")
    }
    private fun isNameChar(c:Char)=c.isLetterOrDigit()||c=='_'||c==':'||c=='-'||c=='.'
    private fun skipTagDeclaration(xml:String,start:Int):Int { var i=start;var quote='\u0000';var brackets=0;while(i<xml.length) { val c=xml[i++];if(quote!='\u0000'){if(c==quote)quote='\u0000'}else when(c){'\'', '\"'->quote=c;'['->brackets++;']'->brackets--; '>'->if(brackets<=0)return i} };throw IllegalArgumentException("Unterminated XML declaration") }
    private fun decodeEntities(s:String):String {
        if('&' !in s)return s
        val out=StringBuilder(s.length);var i=0
        while(i<s.length) { if(s[i]!='&'){out.append(s[i++]);continue};val end=s.indexOf(';',i+1);if(end<0){out.append(s[i++]);continue};val entity=s.substring(i+1,end);val ch=when(entity){"amp"->"&";"lt"->"<";"gt"->">";"quot"->"\"";"apos"->"'";else->if(entity.startsWith("#x")) entity.substring(2).toIntOrNull(16)?.toChar()?.toString() else if(entity.startsWith("#"))entity.substring(1).toIntOrNull()?.toChar()?.toString() else null};if(ch==null){out.append(s,i,end+1)}else out.append(ch);i=end+1 }
        return out.toString()
    }

    /** Formats a float as a fixed six-decimal number, retaining a negative zero sign. */
    fun formatFixed6(v:Float):String {
        require(v.isFinite()) { "Cannot format a non-finite number" }
        val negative=v.toRawBits()<0
        val scaled=kotlin.math.abs(v.toDouble())*1_000_000.0
        require(scaled < Long.MAX_VALUE.toDouble()) { "Number is too large to format" }
        val base=floor(scaled);val fraction=scaled-base
        var rounded=base.toLong()
        if(fraction>0.5 || (fraction==0.5 && rounded%2L!=0L))rounded++
        val whole=rounded/1_000_000L;val decimals=(rounded%1_000_000L).toString().padStart(6,'0')
        return (if(negative)"-" else "")+whole.toString()+"."+decimals
    }

    /** Text for an XML attribute value: names come from files and must not break the document. */
    fun escapeXml(s: String): String = buildString { for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> if (c < ' ') append(' ') else append(c) } }

    fun writePlain(objects:List<SceneObject>):ByteArray {
        val res=StringBuilder();val build=StringBuilder()
        for(i in objects.indices) {
            val obj=objects[i];val id=i+1;res.append("<object id=\"").append(id).append("\" name=\"").append(escapeXml(obj.name)).append("\" type=\"model\"><mesh><vertices>")
            for(v in 0 until obj.mesh.vertexCount)res.append("<vertex x=\"").append(formatFixed6(obj.mesh.positions[v*3])).append("\" y=\"").append(formatFixed6(obj.mesh.positions[v*3+1])).append("\" z=\"").append(formatFixed6(obj.mesh.positions[v*3+2])).append("\"/>")
            res.append("</vertices><triangles>")
            for(t in 0 until obj.mesh.triangleCount)res.append("<triangle v1=\"").append(obj.mesh.indices[t*3]).append("\" v2=\"").append(obj.mesh.indices[t*3+1]).append("\" v3=\"").append(obj.mesh.indices[t*3+2]).append("\"/>")
            res.append("</triangles></mesh></object>")
            val transform=obj.transform
            val t=listOf(transform.m[0],transform.m[1],transform.m[2],transform.m[4],transform.m[5],transform.m[6],transform.m[8],transform.m[9],transform.m[10],transform.m[12],transform.m[13],transform.m[14])
            build.append("<item objectid=\"").append(id).append("\" transform=\"").append(t.joinToString(" "){formatFixed6(it)}).append("\" printable=\"1\"/>")
        }
        val model="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>${res}</resources><build>${build}</build></model>"
        val types="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>"
        val rels="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>"
        return Zip.write(listOf("[Content_Types].xml" to types.encodeToByteArray(),"_rels/.rels" to rels.encodeToByteArray(),"3D/3dmodel.model" to model.encodeToByteArray()))
    }
}
