package edu.jxslu.schedule.data.qiekj

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 胖乖接口的 JSON 配置与脏数据容错（DESIGN §4.10 决策 1）。
 *
 * 根因：该服务端返回的 JSON 不严格——`data` 可能是空串 `""` 而不是对象、
 * 数字字段可能以字符串或裸数字两种形态出现。参考实现 light-life 用 Moshi 的
 * EmptyDataJsonAdapter / LenientStringJsonAdapter 兜住；本工程统一用
 * kotlinx-serialization，等价能力在这里实现：
 * - [LenientStringSerializer]：任何 JsonPrimitive（数字/布尔/字符串）都取 content 当字符串；
 * - [EmptyData] + [EmptyDataSerializer]：`data` 是任意值（`""`、`{}`、`null`、数字）都消费掉不报错。
 */
object QiekjJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
}

/** 任何 JsonPrimitive 都按其文本内容当作字符串读；数组/对象返回 null（等价 LenientStringJsonAdapter）。 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString?", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** 非空版：字段声明为 String（带默认值）时用；裸数字/布尔照读 content，数组/对象回退空串。 */
object LenientStringNonNullSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        return (jsonDecoder.decodeJsonElement() as? JsonPrimitive)?.content ?: ""
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** 占位类型：仅用于消费 `data` 里的任意值（等价 EmptyDataJsonAdapter）。 */
@Serializable(with = EmptyDataSerializer::class)
class EmptyData {
    override fun equals(other: Any?) = other is EmptyData
    override fun hashCode() = 0
    override fun toString() = "EmptyData"
}

object EmptyDataSerializer : KSerializer<EmptyData> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("EmptyData")

    override fun deserialize(decoder: Decoder): EmptyData {
        // 无论对端给 "" / {} / 数字 / null，消费掉即可；业务层只关心 code/msg。
        (decoder as? JsonDecoder)?.decodeJsonElement()
        return EmptyData()
    }

    override fun serialize(encoder: Encoder, value: EmptyData) {
        encoder.encodeSerializableValue(JsonObject.serializer(), JsonObject(emptyMap()))
    }
}
