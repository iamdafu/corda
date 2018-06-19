package net.corda.serialization.internal.carpenter

import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.deserialize
import net.corda.serialization.internal.amqp.testutils.readTestResource
import net.corda.testing.core.SerializationEnvironmentRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

class SerDeserCarpentryTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Test
    fun implementingGenericInterface() {
        // Original class that was serialised
//        data class GenericData(val a: Int) : GenericInterface<String>
//        writeTestResource(GenericData(123).serialize())

        val data = readTestResource().deserialize<Any>()
        assertThat(data).isInstanceOf(GenericInterface::class.java)
        assertThat(data.javaClass.getMethod("getA").invoke(data)).isEqualTo(123)
    }

    @Suppress("unused")
    @CordaSerializable
    interface GenericInterface<T>
}
