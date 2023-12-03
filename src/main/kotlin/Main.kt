// Copyright 2023 by Arkadiusz Bulski <arek.bulski@gmail.com> under MIT License

//-------------------------------------------------------------------------------------------------
//                                          INTERNAL
// TODO: Add the building system.
// TODO: Read https://www.baeldung.com/java-bytebuffer
// TODO: Read documentation of klaxon JSON serialization library, look for clues.

//-------------------------------------------------------------------------------------------------
//                                          INTERNAL

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

//-------------------------------------------------------------------------------------------------
//                                          INTERNAL

var ParsingClassesCache = mutableMapOf<String,KClass<*>>()

fun populateParsingClassesCache() {
    if (ParsingClassesCache.isNotEmpty())
        return
    fun add (annotation:KClass<*>, parsingClass:KClass<*>) {
        val annotationName = annotation.qualifiedName ?:
            throw IllegalStateException("Failed to associate annotation ${annotation} with class ${parsingClass}")
        if (parsingClass.superclasses.firstOrNull()?.qualifiedName != "Construct")
            throw IllegalStateException("The parsing class ${parsingClass} is not Construct derived.")
        ParsingClassesCache[annotationName] = parsingClass
    }
    add(Bytes::class, BytesImpl::class)
    add(BytesEx::class, BytesImpl::class)
    add(Int8ub::class, Int8ubImpl::class)
    add(Struct::class, StructImpl::class)
    add(Array::class, ArrayImpl::class)
}

inline fun internalParseStruct (parsedType: KClass<*>, stream: ByteBuffer, context:ContextDictionary): Any {
    // TODO: Check if a Struct class/dataclass has an argumentless constructor.
//    if (! parsedType.constructors.any { it.parameters.size == 0 })
//        throw IllegalStateException("Parsed type ${parsedType} needs am argumentless constructor.")
    val parsedStruct = parsedType.createInstance()
    // TODO: Optimize this loop below.
    for (javaField in parsedType.java.declaredFields) {
        val field = parsedType.memberProperties.single{it.name == javaField.name}
        if (field.annotations.isEmpty())
            continue
        if (field.annotations.size > 1)
            throw IllegalStateException("The parsed class ${parsedType} member ${field.name} has >1 annotation.")
        if (field !is KMutableProperty<*>)
            throw IllegalStateException("The parsed class ${parsedType} member ${field.name} is read only (ie. a val, not var).")
        val fieldAnnotation = field.annotations.single()
        val fieldAnnotationName = fieldAnnotation.annotationClass.qualifiedName
            ?: throw IllegalStateException("No qualified name for ${fieldAnnotation} annotation.")
        val parsingClass = ParsingClassesCache[fieldAnnotationName]
            ?: throw IllegalStateException("No ${fieldAnnotationName} parsing class found for ${field}.")
        if (! parsingClass.constructors.any { it.parameters.size == 0 })
            throw IllegalStateException("Parsing class ${parsingClass} needs am argumentless constructor.")
        val parsingInstance = parsingClass.createInstance()
        val parsingMethod = parsingClass.memberFunctions.single{ it.name == "parse" }
        val parsedObject = parsingMethod.call(parsingInstance, field.getter.returnType.jvmErasure, fieldAnnotation, stream, context)
            ?: throw IllegalStateException("Parsing method call failed.")
        field.setter.call(parsedStruct, parsedObject)
        context[field.name] = parsedObject
    }
    return parsedStruct
}

inline fun internalParseObject (parsedType:KClass<*>, stream:ByteBuffer, context:ContextDictionary): Any {
    populateParsingClassesCache()
    if (parsedType.annotations.isEmpty())
        throw IllegalStateException("The parsed class ${parsedType} is missing an annotation.")
    if (parsedType.annotations.size > 1)
        throw IllegalStateException("The parsed class ${parsedType} has >1 annotation.")
    val annotation = parsedType.annotations.single()
    val annotationName = annotation.annotationClass.qualifiedName
        ?: throw IllegalStateException("No qualified name for ${annotation} annotation.")
    val parsingClass = ParsingClassesCache[annotationName]
        ?: throw IllegalStateException("No ${annotationName} parsing class found.")
    val parsingInstance = parsingClass.createInstance()
    val parsingMethod = parsingClass.memberFunctions.single{ it.name == "parse" }
    val parsedObject = parsingMethod.call(parsingInstance, parsedType, annotation, stream, context)
        ?: throw IllegalStateException("Parsing method call failed.")
    return parsedObject
}

inline fun internalSizeOf (parsedType:KClass<*>, context:ContextDictionary): Int {
    populateParsingClassesCache()
    val conAnnotation = parsedType.annotations.single()
    val conName = conAnnotation.annotationClass.qualifiedName!!
    val parsingClass = ParsingClassesCache[conName]!!
    val parsingInstance = parsingClass.createInstance()
    val sizeofMethod = parsingClass.memberFunctions.single{it.name == "sizeOf"}
    val conSize = sizeofMethod.call(parsingInstance, parsedType, conAnnotation, context)!! as Int
    return conSize
}

//-------------------------------------------------------------------------------------------------
//                                          PUBLIC

@OptIn(ExperimentalUnsignedTypes::class)
inline fun <reified T:Any> parseBytes (data:UByteArray): T {
    var stream = ByteBuffer.wrap(data.asByteArray())
    return parseStream<T>(stream) as T
}

@OptIn(ExperimentalUnsignedTypes::class)
inline fun <reified T:Any> parseStream (stream:ByteBuffer): T {
    var context: ContextDictionary = mutableMapOf()
    return internalParseObject(T::class, stream, context) as T
}

@OptIn(ExperimentalUnsignedTypes::class)
inline fun <reified T:Any> sizeOf (): Int {
    var context: ContextDictionary = mutableMapOf()
    try {
        return internalSizeOf(T::class, context)
    } catch (e: Exception) {
        throw IllegalStateException("The sizeOf function failed due to ${e} exception.")
    }
}

typealias ContextDictionary = MutableMap<String,Any>

@OptIn(ExperimentalUnsignedTypes::class)
abstract class Construct {
    abstract fun <T:Any> parse (parsedType:KClass<T>, parsedAnnotation:Annotation, stream:ByteBuffer, context:ContextDictionary): Any
    abstract fun <T:Any> sizeOf (parsedType:KClass<T>, parsedAnnotation:Annotation, context:ContextDictionary): Int
}

annotation class Bytes (val size:Int)
annotation class BytesEx (val functor:KClass<*>)

abstract class BytesExFunctor {
    abstract fun size (context: ContextDictionary): Int
}

@OptIn(ExperimentalUnsignedTypes::class)
class BytesImpl : Construct() {
    fun getSize (parsedAnnotation: Annotation, context: ContextDictionary): Int {
        if (parsedAnnotation is Bytes) {
            return parsedAnnotation.size
        }
        if (parsedAnnotation is BytesEx) {
            val instance = parsedAnnotation.functor.createInstance()
            val functor = parsedAnnotation.functor.functions.single{it.name == "size"}
            val value = functor.call(instance, context)!! as Int
            return value
        }
        throw IllegalStateException("BytesImpl does not recognize ${parsedAnnotation} annotation.")
    }
    override fun <T:Any> parse(parsedType: KClass<T>, parsedAnnotation: Annotation, stream: ByteBuffer, context:ContextDictionary): UByteArray {
        var size = getSize(parsedAnnotation, context)
        var buffer = ByteArray(size)
        stream.get(buffer)
        return buffer.asUByteArray()
    }
    override fun <T:Any> sizeOf(parsedType: KClass<T>, parsedAnnotation: Annotation, context: ContextDictionary): Int {
        return getSize(parsedAnnotation, context)
    }
}

annotation class Int8ub

class Int8ubImpl : Construct() {
    override fun <T:Any> parse(parsedType: KClass<T>, parsedAnnotation: Annotation, stream: ByteBuffer, context:ContextDictionary): UByte {
        return stream.get().toUByte()
    }
    override fun <T:Any> sizeOf(parsedType: KClass<T>, parsedAnnotation: Annotation, context: ContextDictionary): Int {
        return 1
    }
}

annotation class Struct

class StructImpl : Construct() {
    override fun <T:Any> parse(parsedType: KClass<T>, parsedAnnotation: Annotation, stream: ByteBuffer, context:ContextDictionary): Any {
        return internalParseStruct(parsedType, stream, context)
    }
    override fun <T:Any> sizeOf(parsedType: KClass<T>, parsedAnnotation: Annotation, context: ContextDictionary): Int {
        var totalSize = 0
        for (javaField in parsedType.java.declaredFields) {
            val subcon = parsedType.memberProperties.single{it.name == javaField.name}
            val subconAnnotation = subcon.annotations.single()
            val subconName = subconAnnotation.annotationClass.qualifiedName
            val parsingClass = ParsingClassesCache[subconName]!!
            val parsingInstance = parsingClass.createInstance()
            val sizeofMethod = parsingClass.memberFunctions.single{it.name == "sizeOf"}
            val subconSize = sizeofMethod.call(parsingInstance, parsedType, subconAnnotation, context)!! as Int
            totalSize += subconSize
        }
        return totalSize
    }
}

annotation class Array(val count:Int, val subcon:KClass<*>)

class ArrayImpl : Construct() {
    override fun <T:Any> parse(parsedType: KClass<T>, parsedAnnotation: Annotation, stream: ByteBuffer, context:ContextDictionary): MutableList<T> {
        val annotation = parsedAnnotation as Array
        if (annotation.count < 0)
            throw IllegalStateException("ArrayImpl count cannot be negative, is ${annotation.count}.")
        var parsedList = mutableListOf<T>()
        for (i in 1..annotation.count) {
            var parsedElement = internalParseObject(annotation.subcon, stream, context) as T
            parsedList.add(parsedElement)
        }
        return parsedList
    }
    override fun <T:Any> sizeOf(parsedType: KClass<T>, parsedAnnotation: Annotation, context: ContextDictionary): Int {
        val annotation = parsedAnnotation as Array
        if (annotation.count < 0)
            throw IllegalStateException("ArrayImpl count cannot be negative, is ${annotation.count}.")
        val subconType = parsedAnnotation.subcon
        val subconAnnotation = subconType.annotations.single()
        val subconName = subconAnnotation.annotationClass.qualifiedName!!
        val parsingClass = ParsingClassesCache[subconName]!!
        val parsingInstance = parsingClass.createInstance()
        val sizeofMethod = parsingClass.memberFunctions.single{it.name == "sizeOf"}
        val subconSize = sizeofMethod.call(parsingInstance, parsedType, annotation, context)!! as Int
        return annotation.count * subconSize
    }
}

//-------------------------------------------------------------------------------------------------
//                                          PUBLIC/INTERNAL

fun main() {
}