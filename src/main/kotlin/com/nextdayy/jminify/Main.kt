package com.nextdayy.jminify

import org.objectweb.asm.*
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.collections.HashSet

val methodSet = HashSet<String>()
val classSet = HashSet<String>()
lateinit var jarFile: JarFile


fun main(args: Array<String>) {
    jarFile = JarFile("./test2.jar")
    var entrypoint = "cc.polyfrost.polyui.TestKt#main"
    // enum constants with abstract methods don't work
    methodSet.add("String org.lwjgl.system.Platform$1.mapLibraryName(String)".toDescriptor())
    methodSet.add("String org.lwjgl.system.Platform$2.mapLibraryName(String)".toDescriptor())
    methodSet.add("String org.lwjgl.system.Platform$3.mapLibraryName(String)".toDescriptor())
    // todo this doesnt work
    methodSet.add("org.lwjgl.system.CustomBuffer org.lwjgl.system.MemoryStack.wrap(java.lang.Class, long, int)".toDescriptor())

    val s = entrypoint.split("#")
    val e = s[0].replace(".", "/")
    entrypoint = "$e.class"
    val method = s[1]
    var jar = jarFile.entries()
    while (jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if (entry.name.equals(entrypoint)) {
            val inputStream = jarFile.getInputStream(entry)
            val classReader = ClassReader(inputStream)
            try {
                classReader.accept(object : ClassVisitor(Opcodes.ASM7) {
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        if (name.equals(method)) {
                            println("found entrypoint: $name$descriptor")
                            methodSet.add("$e.$name$descriptor")
                            return visit()
                        }
                        return object : MethodVisitor(Opcodes.ASM7) {}
                    }
                }, ClassReader.SKIP_DEBUG)
            } catch(_: CancellationException) {}
            catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var i = 0
    var ii = 0
    val fos = FileOutputStream("./out.jar")
    val jos = JarOutputStream(fos)
    jar = jarFile.entries()
    while(jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if(!entry.name.endsWith(".class")) {
            jos.putNextEntry(JarEntry(entry.name))
            jos.write(jarFile.getInputStream(entry).readBytes())
            jos.closeEntry()
        } else if(classSet.contains(entry.name)) {
            val reader = ClassReader(jarFile.getInputStream(entry))
            val writer = ClassWriter(reader, 0)
            val owner = entry.name.substring(0, entry.name.length - 6)
            reader.accept(object : ClassVisitor(Opcodes.ASM7, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    if(!methodSet.contains("$owner.$name$descriptor") && !name.startsWith("<")) {
                        i++
                        return null
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }
            }, 0)
            jos.putNextEntry(JarEntry(entry.name))
            jos.write(writer.toByteArray())
            jos.closeEntry()
        } else ii++
    }
    jos.close()
    fos.close()

    println("Successfully written ${classSet.size} classes and ${methodSet.size} methods to out.jar; with $i methods and $ii classes removed")
}

/**
 * Converts a method to a method descriptor.
 *
 * e.g.
 * ```
 * In: java.lang.Boolean com.example.Test.method(java.lang.String)
 * out: com/example/Test.method(Ljava/lang/String;)Ljava/lang/Boolean;
 *
 * In: byte com.example.Test.method(java.lang.String)
 * out: com/example/Test.method(Ljava/lang/String;)B
 * ```
 */
fun String.toDescriptor(): String {
    var method = this.replace(".", "/")
    val s = method.split(" ", limit = 2)
    val returnType = s[0].toTypeDescriptor()
    method = s[1]
    val name = method.substring(0, method.indexOf("(")).replaceLastOccurrenceOf("/", ".")
    val descriptor = method.substring(method.indexOf("(") + 1, method.length - 1).multiToTypeDescriptor()
    return "$name($descriptor)$returnType"
}

fun String.replaceLastOccurrenceOf(old: String, new: String): String {
    val index = this.lastIndexOf(old)
    if(index == -1) {
        return this
    }
    return this.substring(0, index) + new + this.substring(index + old.length)
}

fun String.multiToTypeDescriptor(): String {
    val strings = this.replace(",", "").split(" ")
    val builder = StringBuilder()
    for(string in strings) {
        builder.append(string.toTypeDescriptor())
    }
    return builder.toString()
}

fun String.toTypeDescriptor(): String {
    return when(this.removeSuffix("[]")) {
        "boolean" -> "Z"
        "byte" -> "B"
        "char" -> "C"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        "void" -> "V"
        "String" -> "Ljava/lang/String;"
        "" -> ""
        else -> "L$this;"
    }.also {
        if(this.endsWith("[]")) {
            return "[$it"
        }
    }
}

@Suppress("NAME_SHADOWING")
fun get(clazz: String): InputStream {
    val clazz = "$clazz.class"
    val jar = jarFile.entries()
    while(jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if (entry.name.equals(clazz)) {
            return jarFile.getInputStream(entry)
        }
    }
    throw Exception("$clazz not found")
}

@Suppress("NAME_SHADOWING")
fun visit(): MethodVisitor {
    return object : MethodVisitor(Opcodes.ASM7) {
        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            var owner = owner
            if(owner.startsWith("[")) {
                owner = owner.substring(2)
            }
            if(owner.startsWith("java")) {
                return
            }
            if(owner.endsWith(";")) {
                owner = owner.substring(0, owner.length - 1)
            }
            classSet.add("$owner.class")
            if(methodSet.add("$owner.$name$descriptor")) visit(owner, name, descriptor)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            for(arg in bootstrapMethodArguments) {
                if(arg is Handle) {
                    var owner = arg.owner
                    if(owner.startsWith("[")) {
                        owner = owner.substring(2)
                    }
                    if(owner.startsWith("java")) {
                        return
                    }
                    if(owner.endsWith(";")) {
                        owner = owner.substring(0, arg.owner.length - 1)
                    }
                    classSet.add("${owner}.class")
                    if(methodSet.add("${owner}.${arg.name}${arg.desc}")) visit(arg.owner, arg.name, arg.desc)
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }
    }
}

fun visit(owner: String, name: String, descriptor: String) {
    val reader: ClassReader = try {
        ClassReader(owner)
    } catch (_: Exception) {
        ClassReader(get(owner))
    }
    reader.accept(object : ClassVisitor(Opcodes.ASM7) {
        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            if(superName != null && !superName.startsWith("java")) {
                classSet.add("$superName.class")
                visit(superName, name, descriptor)
            }
            if(interfaces != null) for(i in interfaces) {
                if(!i.startsWith("java")) {
                    classSet.add("$i.class")
                    visit(i, name, descriptor)
                }
            }
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitInnerClass(theName: String?, outerName: String?, innerName: String?, access: Int) {
            if(theName != null && !theName.startsWith("java")) {
                if(classSet.add("$theName.class")) {
                    visit(theName, name, descriptor)
                }
            }
            super.visitInnerClass(theName, outerName, innerName, access)
        }

        override fun visitMethod(
            access: Int,
            theName: String,
            theDescriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return if (theName.equals("<clinit>") || (theName.equals(name) && theDescriptor.equals(descriptor))) {
                visit()
            } else object : MethodVisitor(Opcodes.ASM7) {}
        }
    }, ClassReader.SKIP_DEBUG)
}