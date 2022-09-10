package org.ergoplatform.dex.domain

import cats.effect.Sync
import derevo.cats.show
import derevo.circe.{decoder, encoder}
import derevo.derive
import doobie.{Get, Put}
import fs2.kafka.serde.{deserializerViaKafkaDecoder, serializerViaCirceEncoder}
import fs2.kafka.{RecordDeserializer, RecordSerializer}
import io.estatico.newtype.macros.newtype
import org.ergoplatform.common.HexString
import org.ergoplatform.dex.domain.amm.PoolId
import org.ergoplatform.ergo.{BoxId, TokenId}
import scodec.bits.ByteVector
import scodec.codecs.{uint16, utf8}
import sttp.tapir.{Codec, Schema, Validator}
import tofu.logging.derivation.loggable

package object amm {

  @derive(show, loggable, encoder, decoder)
  @newtype final case class PoolStateId(value: BoxId) {
    def unwrapped: String = value.value
  }

  object PoolStateId {
    implicit val put: Put[PoolStateId] = deriving
    implicit val get: Get[PoolStateId] = deriving

    def fromBoxId(boxId: BoxId): PoolStateId = PoolStateId(boxId)
  }

  @derive(show, loggable, encoder, decoder)
  @newtype final case class ProtocolVersion(value: Int)

  object ProtocolVersion {
    implicit val put: Put[ProtocolVersion] = deriving
    implicit val get: Get[ProtocolVersion] = deriving

    val Initial: ProtocolVersion = ProtocolVersion(1)
  }

  @derive(show, loggable, encoder, decoder)
  @newtype final case class PoolId(value: TokenId) {
    def unwrapped: String = value.unwrapped
  }

  object PoolId {

    implicit val put: Put[PoolId] = deriving
    implicit val get: Get[PoolId] = deriving

    implicit def plainCodec: Codec.PlainCodec[PoolId] = deriving

    implicit def codec: scodec.Codec[PoolId] =
      scodec.codecs
        .bytes(32)
        .xmap(xs => fromBytes(xs.toArray), pid => ByteVector(pid.value.value.toBytes))

    implicit def schema: Schema[PoolId] =
      Schema.schemaForString.description("Pool ID").asInstanceOf[Schema[PoolId]]

    implicit def validator: Validator[PoolId] =
      implicitly[Validator[TokenId]].contramap[PoolId](_.value)

    implicit def recordSerializer[F[_]: Sync]: RecordSerializer[F, PoolId]     = serializerViaCirceEncoder
    implicit def recordDeserializer[F[_]: Sync]: RecordDeserializer[F, PoolId] = deserializerViaKafkaDecoder

    def fromBytes(bytes: Array[Byte]): PoolId =
      PoolId(TokenId.fromBytes(bytes))

    def fromHex(s: HexString): PoolId = PoolId(TokenId(s))

    def fromStringUnsafe(s: String): PoolId = PoolId(TokenId.fromStringUnsafe(s))
  }

  @derive(show, loggable, encoder, decoder)
  @newtype case class OrderId(value: String)

  object OrderId {

    def fromBoxId(boxId: BoxId): OrderId = OrderId(boxId.value)

    implicit val get: Get[OrderId] = deriving
    implicit val put: Put[OrderId] = deriving

    implicit def codec: scodec.Codec[OrderId] =
      scodec.codecs.variableSizeBits(uint16, utf8).xmap(OrderId(_), _.value)

    implicit def recordSerializer[F[_]: Sync]: RecordSerializer[F, OrderId]     = serializerViaCirceEncoder
    implicit def recordDeserializer[F[_]: Sync]: RecordDeserializer[F, OrderId] = deserializerViaKafkaDecoder
  }

  @derive(show, loggable, encoder, decoder)
  case class WeightedOrder(weight: Long, orderId: OrderId, timestamp: Long)

  object WeightedOrder {

    def fromOrder(order: CFMMOrder): WeightedOrder =
      WeightedOrder(order.maxMinerFee, order.id, order.timestamp)

    implicit val ord: Ordering[WeightedOrder] =
      (x: WeightedOrder, y: WeightedOrder) => x.weight compare y.weight

    implicit def recordSerializer[F[_]: Sync]: RecordSerializer[F, OrderId]     = serializerViaCirceEncoder
    implicit def recordDeserializer[F[_]: Sync]: RecordDeserializer[F, OrderId] = deserializerViaKafkaDecoder
  }
}
