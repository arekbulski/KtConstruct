// Copyright 2023 by Arkadiusz Bulski <arek.bulski@gmail.com> under MIT License

import org.junit.jupiter.api.Test
import kotlin.test.assertFails

class MainKtTest {

    class ThisNum : BytesExFunctor() {
        override fun size(context: ContextDictionary): Int {
            return (context["zzz"]!! as UByte).toInt()
        }
    }

    @Struct
    class Struct1 {
        @Int8ub
        var zzz = 0.toUByte()
        @BytesEx(ThisNum::class)
        var aaa = UByteArray(0)
        @Struct
        var inner1 = Inner()
        @Struct
        var dc = DataClass1()
        @Array(10, Inner::class)
        var ar1 = mutableListOf<Inner>()
    }

    @Struct
    class Inner {
        @Int8ub
        var x = 0.toUByte()
        @Bytes(2)
        var b = UByteArray(0)
    }

    @Struct
    data class DataClass1 (
        @property:Int8ub
        var x: UByte = 0.toUByte()
    )

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun Struct1test() {
        val s1 = parseBytes<Struct1>(ubyteArrayOf(1u,2u)+UByteArray(100))
        assert(s1.zzz == 1.toUByte())
        assert(s1.aaa.size == 1)
        assert(s1.aaa.contentEquals(ubyteArrayOf(2u)))
        assert(s1.inner1.x == 0.toUByte())
        assert(s1.dc.x == 0.toUByte())
        assert(s1.ar1.size == 10)
        assert(s1.ar1[0].x == 0.toUByte())
        assert(s1.ar1[0].b.size == 2)
        assert(assertFails{ sizeOf<Struct1>() } is IllegalStateException)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun DataClass1test() {
        val dc2 = parseBytes<DataClass1>(ubyteArrayOf(1u))
        assert(dc2.x == 1.toUByte())
    }

}