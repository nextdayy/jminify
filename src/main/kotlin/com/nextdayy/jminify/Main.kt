package com.nextdayy.jminify

import org.objectweb.asm.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

val methodSet = HashSet<String>()
val classSet = HashSet<String>()
val redirects = HashMap<String, String>()
lateinit var jarFile: JarFile


@Suppress("NAME_SHADOWING")
fun main(args: Array<String>) {
    jarFile = JarFile("./test2.jar")
    val entrypoint = "void cc.polyfrost.polyui.TestKt.main(String[])".toDescriptor()
    // enum constants with abstract methods don't work
    methodSet.add("String org.lwjgl.system.Platform$1.mapLibraryName(String)".toDescriptor())
    methodSet.add("String org.lwjgl.system.Platform$2.mapLibraryName(String)".toDescriptor())
    methodSet.add("String org.lwjgl.system.Platform$3.mapLibraryName(String)".toDescriptor())
    methodSet.add("org.lwjgl.system.Struct\$Member org.lwjgl.system.libffi.FFIType.__member(int)")
    // todo this doesnt work
    redirects["org.lwjgl.system.CustomBuffer org.lwjgl.system.MemoryStack.wrap(java.lang.Class, long, int)".toDescriptor()] =
        "org.lwjgl.system.CustomBuffer org.lwjgl.system.Pointer\$Default.wrap(java.lang.Class, long, int)".toDescriptor()

    val s = entrypoint.split("(")
    val clazz = s[0].substring(0, s[0].lastIndexOf(".")) + ".class"
    val method = s[0].substring(s[0].lastIndexOf(".") + 1)
    val methodDesc = "(${s[1]}"
    var jar = jarFile.entries()
    var done = false
    while (jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if (entry.name.equals(clazz)) {
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
                        if (done) throw CancellationException()
                        if (name.equals(method) && descriptor.equals(methodDesc)) {
                            println("found entrypoint: $entrypoint")
                            methodSet.add(entrypoint)
                            done = true
                            return visitor()
                        }
                        return object : MethodVisitor(Opcodes.ASM7) {}
                    }
                }, ClassReader.SKIP_DEBUG)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    if (!done) throw Exception("Entrypoint not found!")

    var i = 0
    var ii = 0
    val out = File("./out.jar")
    out.delete()
    out.createNewFile()
    val fos = FileOutputStream(out)
    val jos = JarOutputStream(fos)
    jar = jarFile.entries()
    while (jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if (!entry.name.endsWith(".class") && !entry.name.contains(".kotlin_")) {
            jos.putNextEntry(JarEntry(entry.name))
            jos.write(jarFile.getInputStream(entry).readBytes())
            jos.closeEntry()
        } else if (classSet.contains(entry.name)) {
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
                    var access = access
                    val m = "$owner.$name$descriptor"
                    if (redirects.containsValue(m)) {
                        println("upgrading access of $owner.$name$descriptor to public for redirect")
                        access = access and Opcodes.ACC_PRIVATE.inv()
                        access = access and Opcodes.ACC_PROTECTED.inv()
                        access = access or Opcodes.ACC_PUBLIC
                    }
                    if (!methodSet.contains(m) && !name.startsWith("<") && !redirects.containsValue(m)) {
                        println("removing $m")
                        i++
                        return null
                    }

                    //return super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(
                        Opcodes.ASM7,
                        super.visitMethod(access, name, descriptor, signature, exceptions)
                    ) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean
                        ) {
                            var owner = owner
                            var name = name
                            var descriptor = descriptor
                            val s = "$owner.$name$descriptor"
                            val s2 = redirects[s]
                            if (s2 != null) {
                                println("redirecting $s to $s2")
                                owner = s2.substringBefore(".")
                                name = s2.substringAfter(".").substringBefore("(")
                                descriptor = s2.substring(s2.indexOf("("))
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
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

fun String.replaceLastOccurrenceOf(string: String, replacement: String): String {
    val index = this.lastIndexOf(string)
    if (index == -1) {
        return this
    }
    return this.substring(0, index) + replacement + this.substring(index + string.length)
}

fun String.multiToTypeDescriptor(): String {
    val strings = this.split(",")
    val builder = StringBuilder()
    for (string in strings) {
        builder.append(string.trim().toTypeDescriptor())
    }
    return builder.toString()
}

fun String.toTypeDescriptor(): String {
    return when (this.removeSuffix("[]")) {
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
        if (this.endsWith("[]")) {
            return "[$it"
        }
    }
}

@Suppress("NAME_SHADOWING")
fun get(clazz: String): InputStream {
    val clazz = "$clazz.class"
    val jar = jarFile.entries()
    while (jar.hasMoreElements()) {
        val entry = jar.nextElement()
        if (entry.name.equals(clazz)) {
            return jarFile.getInputStream(entry)
        }
    }
    throw Exception("$clazz not found")
}


@Suppress("NAME_SHADOWING")
fun visitor(): MethodVisitor {
    return object : MethodVisitor(Opcodes.ASM7) {
        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val owner = owner.sanitize()
            if (owner.startsWith("java") || owner.startsWith("sun") || owner.startsWith("jdk")) {
                return
            }
            classSet.add("$owner.class")
            if (methodSet.add("$owner.$name$descriptor")) {
                visit(owner, name, descriptor)
            }
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any
        ) {
            for (arg in bootstrapMethodArguments) {
                if (arg is Handle) {
                    val owner = arg.owner.sanitize()
                    if (owner.startsWith("java")) {
                        return
                    }
                    classSet.add("${owner}.class")
                    if (methodSet.add("${owner}.${arg.name}${arg.desc}")) {
                        visit(owner, arg.name, arg.desc)
                    }
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }
    }
}

fun String.sanitize(): String {
    var out = this
    if (out.startsWith("[")) {
        out = substring(2)
    }
    if (out.endsWith(";")) {
        out = out.substring(0, out.length - 1)
    }
    return out
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
            theName: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            if (superName != null && !superName.startsWith("java")) {
                if (classSet.add("$superName.class")) {
                    visit(superName, theName, descriptor)
                }
            }
            if (interfaces != null) for (i in interfaces) {
                if (!i.startsWith("java")) {
                    if (classSet.add("$i.class")) {
                        visit(i, theName, descriptor)
                    }
                }
            }
            super.visit(version, access, theName, signature, superName, interfaces)
        }

        override fun visitInnerClass(theName: String, outerName: String?, innerName: String?, access: Int) {
            if (!theName.startsWith("java")) {
                if (classSet.add("$theName.class")) {
                    visit(owner, theName, descriptor)
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
            // todo
            return visitor()
        }
    }, ClassReader.SKIP_DEBUG)

}