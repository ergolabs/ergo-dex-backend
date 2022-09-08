package org.ergoplatform.dex.markets.services

import cats.effect.{Clock, Sync}
import cats.effect.concurrent.Ref
import cats.{FlatMap, Functor, Monad}
import derevo.derive
import org.ergoplatform.common.caching.Memoize
import org.ergoplatform.dex.domain.{AssetClass, FiatUnits}
import org.ergoplatform.dex.markets.currencies.UsdUnits
import org.ergoplatform.dex.protocol.constants.{ErgoAssetClass, ErgoAssetDecimals}
import org.ergoplatform.ergo.domain.{RegisterId, SConstant}
import org.ergoplatform.ergo.TokenId
import org.ergoplatform.ergo.services.explorer.ErgoExplorer
import tofu.concurrent.MakeRef
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.logging.{Logging, Logs}
import tofu.syntax.foption._
import tofu.syntax.logging._
import tofu.syntax.monadic._

import scala.concurrent.duration._

@derive(representableK)
trait FiatRates[F[_]] {

  def rateOf(asset: AssetClass, units: FiatUnits): F[Option[BigDecimal]]
}

object FiatRates {

  val ErgUsdPoolNft: TokenId =
    TokenId.fromStringUnsafe("011d3364de07e5a26f0c4eef0852cddb387039a921b7154ef3cab22c6eda887f")

  val MemoTtl: FiniteDuration = 2.minutes

  def make[I[_]: FlatMap: Sync, F[_]: Monad: Clock: Sync](implicit
    network: ErgoExplorer[F],
    logs: Logs[I, F],
    makeRef: MakeRef[I, F]
  ): I[FiatRates[F]] =
    for {
      implicit0(l: Logging[F]) <- logs.forService[FiatRates[F]]
      memo                     <- Memoize.make[I, F, BigDecimal]
      ref                      <- Ref.in[I, F, BigDecimal](BigDecimal(0))
    } yield new FiatRatesTracing[F] attach new ErgoOraclesRateSource(network, memo, ref)

  final class ErgoOraclesRateSource[F[_]: Monad: Logging](
    network: ErgoExplorer[F],
    memo: Memoize[F, BigDecimal],
    ref: Ref[F, BigDecimal]
  ) extends FiatRates[F] {

    def rateOf(asset: AssetClass, units: FiatUnits): F[Option[BigDecimal]] =
      if (asset == ErgoAssetClass && units == UsdUnits) {
        val pullFromNetwork = {
          info"Going to pull from network rate" >>
          network
            .getUtxoByToken(ErgUsdPoolNft, offset = 0, limit = 1)
            .map(_.headOption)
            .map {
              for {
                out    <- _
                (_, r) <- out.additionalRegisters.find { case (r, _) => r == RegisterId.R4 }
                usdPrice <- r match {
                              case SConstant.LongConstant(v) => Some(v)
                              case _                         => None
                            }
                oneErg = math.pow(10, ErgoAssetDecimals.toDouble)
              } yield BigDecimal(oneErg) / BigDecimal(usdPrice)
            }
        }
        for {
          memoizedRate <- info"Memo read $asset $units" >> memo.read.flatTap(r => info"Memo read completed $r")
            res <- memoizedRate match {
                   case Some(rate) => rate.someF
                   case None =>
                     pullFromNetwork.flatTap {
                       case Some(rate) => info"Memo memoize $asset $units $rate" >> memo.memoize(rate, MemoTtl).flatTap(_ => info"Memo memoize completed")
                       case None       => unit
                     }
                 }
        } yield res
      } else noneF
  }

  final class FiatRatesTracing[F[_]: FlatMap: Logging] extends FiatRates[Mid[F, *]] {

    def rateOf(asset: AssetClass, units: FiatUnits): Mid[F, Option[BigDecimal]] =
      for {
        _ <- trace"rateOf(asset=$asset, units=$units)"
        r <- _
        _ <- trace"rateOf(asset=$asset, units=$units) -> $r"
      } yield r
  }
}
