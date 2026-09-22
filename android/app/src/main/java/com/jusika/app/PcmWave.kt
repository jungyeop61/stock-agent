package com.jusika.app

import java.io.ByteArrayOutputStream

object PcmWave {
    fun encode(samples: ShortArray, sampleRate: Int = 16_000): ByteArray {
        require(sampleRate in 8_000..48_000)
        require(samples.isNotEmpty())
        val dataSize = samples.size * 2
        val output = ByteArrayOutputStream(44 + dataSize)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun little16(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }
        fun little32(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            output.write((value ushr 16) and 0xff)
            output.write((value ushr 24) and 0xff)
        }
        ascii("RIFF")
        little32(36 + dataSize)
        ascii("WAVEfmt ")
        little32(16)
        little16(1)
        little16(1)
        little32(sampleRate)
        little32(sampleRate * 2)
        little16(2)
        little16(16)
        ascii("data")
        little32(dataSize)
        for (sample in samples) little16(sample.toInt())
        return output.toByteArray()
    }
}
