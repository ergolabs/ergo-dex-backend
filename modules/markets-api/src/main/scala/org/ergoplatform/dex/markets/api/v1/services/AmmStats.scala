package org.ergoplatform.dex.markets.api.v1.services

import cats.data.OptionT
import cats.effect.Clock
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{Monad, Parallel}
import cats.syntax.option._
import mouse.anyf._
import org.ergoplatform.common.models.TimeWindow
import org.ergoplatform.dex.domain.amm.PoolId
import org.ergoplatform.dex.domain.{AssetClass, CryptoUnits, FullAsset, MarketId}
import org.ergoplatform.dex.markets.api.v1.models.amm._
import org.ergoplatform.dex.domain.{AssetClass, CryptoUnits, FullAsset, MarketId}
import org.ergoplatform.dex.markets.api.v1.models.amm.types._
import org.ergoplatform.dex.markets.api.v1.models.amm._
import org.ergoplatform.dex.markets.api.v1.models.charts.ChartGap
import org.ergoplatform.dex.markets.currencies.UsdUnits
import org.ergoplatform.dex.markets.db.models.amm
import org.ergoplatform.dex.markets.db.models.amm.{PoolFeesSnapshot, PoolSnapshot, PoolTrace, PoolVolumeSnapshot}
import org.ergoplatform.dex.markets.db.models.amm._
import org.ergoplatform.dex.markets.domain.{CryptoVolume, Fees, TotalValueLocked, Volume}
import org.ergoplatform.dex.markets.modules.AmmStatsMath
import org.ergoplatform.dex.markets.modules.PriceSolver.FiatPriceSolver
import org.ergoplatform.dex.markets.repositories.{Orders, Pools}
import org.ergoplatform.dex.markets.services.TokenFetcher
import org.ergoplatform.ergo.TokenId
import org.ergoplatform.ergo.modules.ErgoNetwork
import tofu.doobie.transactor.Txr
import tofu.doobie.transactor.Txr
import tofu.syntax.foption._
import tofu.syntax.monadic._

import mouse.anyf._
import cats.syntax.traverse._
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

trait AmmStats[F[_]] {

  def convertToFiat(id: TokenId, amount: Long): F[Option[FiatEquiv]]

  def getPlatformSummary(window: TimeWindow): F[PlatformSummary]

  def getPoolSummary(poolId: PoolId, window: TimeWindow): F[Option[PoolSummary]]

  def getPoolsSummary(window: TimeWindow): F[List[PoolSummary]]

  def getAvgPoolSlippage(poolId: PoolId, depth: Int): F[Option[PoolSlippage]]

  def getPoolPriceChart(poolId: PoolId, window: TimeWindow, resolution: Int): F[List[PricePoint]]

  def getChartsByGap(gap: ChartGap, poolId: PoolId, window: TimeWindow): F[List[PricePoint]]

  def getMarkets(window: TimeWindow): F[List[AmmMarketSummary]]

  def getSwapTransactions(window: TimeWindow): F[TransactionsInfo]

  def getDepositTransactions(window: TimeWindow): F[TransactionsInfo]
}

object AmmStats {

  val MillisInYear: FiniteDuration = 365.days

  val slippageWindowScale = 2

  def make[F[_]: Monad: Clock: Parallel, D[_]: Monad](implicit
    txr: Txr.Aux[F, D],
    pools: Pools[D],
    orders: Orders[D],
    network: ErgoNetwork[F],
    tokens: TokenFetcher[F],
    fiatSolver: FiatPriceSolver[F]
  ): AmmStats[F] = new Live[F, D]()

  final class Live[F[_]: Monad: Clock: Parallel, D[_]: Monad](implicit
    txr: Txr.Aux[F, D],
    pools: Pools[D],
    orders: Orders[D],
    tokens: TokenFetcher[F],
    network: ErgoNetwork[F],
    fiatSolver: FiatPriceSolver[F],
    ammMath: AmmStatsMath[F]
  ) extends AmmStats[F] {

    def convertToFiat(id: TokenId, amount: Long): F[Option[FiatEquiv]] =
      (for {
        assetInfo <- OptionT(pools.assetById(id) ||> txr.trans)
        asset = FullAsset(
                  assetInfo.id,
                  amount * math.pow(10, assetInfo.evalDecimals).toLong,
                  assetInfo.ticker,
                  assetInfo.decimals
                )
        equiv <- OptionT(fiatSolver.convert(asset, UsdUnits, List.empty))
      } yield FiatEquiv(equiv.value, UsdUnits)).value

    def getPlatformSummary(window: TimeWindow): F[PlatformSummary] = {
      val queryPlatformStats =
        for {
          poolSnapshots <- pools.snapshots
          volumes       <- pools.volumes(window)
        } yield (poolSnapshots, volumes)
      for {
        (poolSnapshots, volumes) <- queryPlatformStats ||> txr.trans
        validTokens              <- tokens.fetchTokens
        filteredSnaps =
          poolSnapshots.filter(ps => validTokens.contains(ps.lockedX.id) && validTokens.contains(ps.lockedY.id))
        lockedX <-
          filteredSnaps.flatTraverse(pool => fiatSolver.convert(pool.lockedX, UsdUnits, List.empty).map(_.toList))
        lockedY <-
          filteredSnaps.flatTraverse(pool => fiatSolver.convert(pool.lockedY, UsdUnits, List.empty).map(_.toList))
        tvl = TotalValueLocked(lockedX.map(_.value).sum + lockedY.map(_.value).sum, UsdUnits)

        volumeByX <-
          volumes.flatTraverse(pool => fiatSolver.convert(pool.volumeByX, UsdUnits, List.empty).map(_.toList))
        volumeByY <-
          volumes.flatTraverse(pool => fiatSolver.convert(pool.volumeByY, UsdUnits, List.empty).map(_.toList))
        volume = Volume(volumeByX.map(_.value).sum + volumeByY.map(_.value).sum, UsdUnits, window)
      } yield PlatformSummary(tvl, volume)
    }

    def getPoolsSummary(window: TimeWindow): F[List[PoolSummary]] =
      (pools.snapshots ||> txr.trans).flatMap { snapshots: List[PoolSnapshot] =>
        snapshots
          .parTraverse(pool => getPoolSummaryUsingAllPools(pool, window, snapshots))
          .map(_.flatten)
      }

    private def getPoolSummaryUsingAllPools(
      pool: PoolSnapshot,
      window: TimeWindow,
      everyKnownPool: List[PoolSnapshot]
    ): F[Option[PoolSummary]] = {
      val poolId = pool.id

      def poolData: D[Option[(amm.PoolInfo, Option[amm.PoolFeesSnapshot], Option[PoolVolumeSnapshot])]] =
        (for {
          info     <- OptionT(pools.info(poolId))
          feesSnap <- OptionT.liftF(pools.fees(poolId, window))
          vol      <- OptionT.liftF(pools.volume(poolId, window))
        } yield (info, feesSnap, vol)).value

      (for {
        (info, feesSnap, vol) <- OptionT(poolData ||> txr.trans)
        lockedX               <- OptionT(fiatSolver.convert(pool.lockedX, UsdUnits, everyKnownPool))
        lockedY               <- OptionT(fiatSolver.convert(pool.lockedY, UsdUnits, everyKnownPool))
        tvl = TotalValueLocked(lockedX.value + lockedY.value, UsdUnits)
        volume            <- processPoolVolume(vol, window, everyKnownPool)
        fees              <- processPoolFee(feesSnap, window, everyKnownPool)
        yearlyFeesPercent <- OptionT.liftF(ammMath.feePercentProjection(tvl, fees, info, MillisInYear))
      } yield PoolSummary(poolId, pool.lockedX, pool.lockedY, tvl, volume, fees, yearlyFeesPercent)).value
    }

    private def processPoolVolume(
      vol: Option[PoolVolumeSnapshot],
      window: TimeWindow,
      everyKnownPool: List[PoolSnapshot]
    ): OptionT[F, Volume] =
      vol match {
        case Some(vol) =>
          for {
            volX <- OptionT(fiatSolver.convert(vol.volumeByX, UsdUnits, everyKnownPool))
            volY <- OptionT(fiatSolver.convert(vol.volumeByY, UsdUnits, everyKnownPool))
          } yield Volume(volX.value + volY.value, UsdUnits, window)
        case None => OptionT.pure[F](Volume.empty(UsdUnits, window))
      }

    private def processPoolFee(
      feesSnap: Option[PoolFeesSnapshot],
      window: TimeWindow,
      everyKnownPool: List[PoolSnapshot]
    ): OptionT[F, Fees] = feesSnap match {
      case Some(feesSnap) =>
        for {
          feesX <- OptionT(fiatSolver.convert(feesSnap.feesByX, UsdUnits, everyKnownPool))
          feesY <- OptionT(fiatSolver.convert(feesSnap.feesByY, UsdUnits, everyKnownPool))
        } yield Fees(feesX.value + feesY.value, UsdUnits, window)
      case None => OptionT.pure[F](Fees.empty(UsdUnits, window))
    }

    def getPoolSummary(poolId: PoolId, window: TimeWindow): F[Option[PoolSummary]] = {
      val queryPoolStats =
        (for {
          info     <- OptionT(pools.info(poolId))
          pool     <- OptionT(pools.snapshot(poolId))
          vol      <- OptionT.liftF(pools.volume(poolId, window))
          feesSnap <- OptionT.liftF(pools.fees(poolId, window))
        } yield (info, pool, vol, feesSnap)).value
      (for {
        (info, pool, vol, feesSnap) <- OptionT(queryPoolStats ||> txr.trans)
        lockedX                     <- OptionT(fiatSolver.convert(pool.lockedX, UsdUnits, List.empty))
        lockedY                     <- OptionT(fiatSolver.convert(pool.lockedY, UsdUnits, List.empty))
        tvl = TotalValueLocked(lockedX.value + lockedY.value, UsdUnits)
        volume            <- processPoolVolume(vol, window, List.empty)
        fees              <- processPoolFee(feesSnap, window, List.empty)
        yearlyFeesPercent <- OptionT.liftF(ammMath.feePercentProjection(tvl, fees, info, MillisInYear))
      } yield PoolSummary(poolId, pool.lockedX, pool.lockedY, tvl, volume, fees, yearlyFeesPercent)).value
    }

    private def calculatePoolSlippagePercent(initState: PoolTrace, finalState: PoolTrace): BigDecimal = {
      val minPrice = RealPrice.calculate(
        initState.lockedX.amount,
        initState.lockedX.decimals,
        initState.lockedY.amount,
        initState.lockedY.decimals
      )
      val maxPrice = RealPrice.calculate(
        finalState.lockedX.amount,
        finalState.lockedX.decimals,
        finalState.lockedY.amount,
        finalState.lockedY.decimals
      )
      (maxPrice.value - minPrice.value).abs / (minPrice.value / 100)
    }

    def getAvgPoolSlippage(poolId: PoolId, depth: Int): F[Option[PoolSlippage]] =
      network.getCurrentHeight.flatMap { currHeight =>
        val query = for {
          initialState <- pools.prevTrace(poolId, depth, currHeight)
          traces       <- pools.trace(poolId, depth, currHeight)
          poolOpt      <- pools.info(poolId)
        } yield (traces, initialState, poolOpt)

        txr.trans(query).map { case (traces, initStateOpt, poolOpt) =>
          poolOpt.flatMap { _ =>
            traces match {
              case Nil => Some(PoolSlippage.zero)
              case xs =>
                val groupedTraces = xs
                  .sortBy(_.gindex)
                  .groupBy(_.height / slippageWindowScale)
                  .toList
                  .sortBy(_._1)
                val initState                  = initStateOpt.getOrElse(xs.minBy(_.gindex))
                val maxState                   = groupedTraces.head._2.maxBy(_.gindex)
                val firstWindowSlippagePercent = calculatePoolSlippagePercent(initState, maxState)

                groupedTraces.drop(1) match {
                  case Nil => Some(PoolSlippage(firstWindowSlippagePercent).scale(PoolSlippage.defaultScale))
                  case restTraces =>
                    val restWindowsSlippage = restTraces
                      .map { case (_, heightWindow) =>
                        val windowMinGindex = heightWindow.minBy(_.gindex).gindex
                        val min = xs.filter(_.gindex < windowMinGindex) match {
                          case Nil      => heightWindow.minBy(_.gindex)
                          case filtered => filtered.maxBy(_.gindex)
                        }
                        val max = heightWindow.maxBy(_.gindex)
                        calculatePoolSlippagePercent(min, max)
                      }
                    val slippageBySegment = firstWindowSlippagePercent +: restWindowsSlippage
                    Some(PoolSlippage(slippageBySegment.sum / slippageBySegment.size).scale(PoolSlippage.defaultScale))
                }
            }
          }
        }
      }

    def getPoolPriceChart(poolId: PoolId, window: TimeWindow, resolution: Int): F[List[PricePoint]] = {

      val queryPoolData = for {
        amounts   <- pools.avgAmounts(poolId, window, resolution)
        snapshots <- pools.snapshot(poolId)
      } yield (amounts, snapshots)

      txr
        .trans(queryPoolData)
        .map {
          case (amounts, Some(snap)) =>
            amounts.map { amount =>
              val price =
                RealPrice.calculate(amount.amountX, snap.lockedX.decimals, amount.amountY, snap.lockedY.decimals)
              PricePoint(amount.timestamp, price.setScale(RealPrice.defaultScale))
            }
          case _ => List.empty
        }
    }

    def getChartsByGap(gap: ChartGap, poolId: PoolId, window: TimeWindow): F[List[PricePoint]] =
      Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { now =>
        val from      = window.from.getOrElse(now - gap.minimalGap)
        val to        = window.to.getOrElse(from + gap.minimalGap)
        val formatter = new SimpleDateFormat(gap.javaDateFormat)

        val queryPoolData = for {
          points    <- pools.getChartsByGap(gap, poolId, from, to)
          snapshots <- pools.snapshot(poolId)
          latestOpt <- if (points.isEmpty) pools.getLatestChartByGap(gap, poolId).map {
                         _.map { asset =>
                           AvgAssetAmounts(asset.amountX, asset.amountY, formatter.parse(asset.timestamp).getTime, 0)
                         }
                       }
                       else noneF[D, AvgAssetAmounts]
        } yield (latestOpt, points, snapshots)

        txr
          .trans(queryPoolData)
          .map {
            case (Some(latest: AvgAssetAmounts), Nil, s @ Some(_: PoolSnapshot)) =>
              (latest :: Nil, s)
            case (_, points: List[AvgAssetAmountsWithPrev], s @ Some(_: PoolSnapshot)) =>
              val timeWindow: Long  = to - from
              val gapsNum: Int      = (timeWindow / gap.timeWindow.toMillis).toInt
              val roundedFrom: Long = ChartGap.round(gap, from)

              (0 to gapsNum)
                .foldLeft(roundedFrom, List.empty[AvgAssetAmounts]) { case ((currentTime, acc), _) =>
                  val point = points.find(p => formatter.parse(p.current).getTime == currentTime)

                  val avg: Option[AvgAssetAmounts] =
                    (acc, point) match {
                      case (Nil, None) =>
                        points.lastOption
                          .flatMap(_.getPrev(new SimpleDateFormat(gap.javaDateFormat)))
                          .map(_.copy(timestamp = currentTime))
                      case (x :: _, None) => x.copy(timestamp = currentTime) some
                      case (_, Some(p))   => AvgAssetAmounts(p.amountX, p.amountY, currentTime, 0).some
                      case _              => none
                    }

                  (ChartGap.updateWithGap(gap, currentTime), avg.fold(acc)(_ :: acc))
                }
                ._2 -> s

            case _ => List.empty -> none[PoolSnapshot]
          }
          .map {
            case (amounts, Some(snap: PoolSnapshot)) =>
              amounts.reverse.map { amount =>
                val price =
                  RealPrice.calculate(amount.amountX, snap.lockedX.decimals, amount.amountY, snap.lockedY.decimals)
                PricePoint(amount.timestamp, price.setScale(RealPrice.defaultScale))
              }
            case _ => List.empty
          }
      }

    def getMarkets(window: TimeWindow): F[List[AmmMarketSummary]] = {
      val queryPoolStats = for {
        volumes   <- pools.volumes(window)
        snapshots <- pools.snapshots
      } yield (volumes, snapshots)

      txr
        .trans(queryPoolStats)
        .map { case (volumes: List[PoolVolumeSnapshot], snapshots: List[PoolSnapshot]) =>
          snapshots.flatMap { snapshot =>
            val currentOpt = volumes
              .find(_.poolId == snapshot.id)

            currentOpt.toList.map { vol =>
              val tx = snapshot.lockedX
              val ty = snapshot.lockedY
              val vx = vol.volumeByX
              val vy = vol.volumeByY
              AmmMarketSummary(
                MarketId(tx.id, ty.id),
                tx.id,
                tx.ticker,
                ty.id,
                ty.ticker,
                RealPrice.calculate(tx.amount, tx.decimals, ty.amount, ty.decimals).setScale(6),
                CryptoVolume(
                  BigDecimal(vx.amount),
                  CryptoUnits(AssetClass(vx.id, vx.ticker, vx.decimals)),
                  window
                ),
                CryptoVolume(
                  BigDecimal(vy.amount),
                  CryptoUnits(AssetClass(vy.id, vy.ticker, vy.decimals)),
                  window
                )
              )
            }
          }
        }
    }

    def getSwapTransactions(window: TimeWindow): F[TransactionsInfo] =
      (for {
        swaps  <- OptionT.liftF(txr.trans(orders.getSwapTxs(window)))
        numTxs <- OptionT.fromOption[F](swaps.headOption.map(_.numTxs))
        volumes <- OptionT.liftF(
                     swaps.flatTraverse(swap =>
                       fiatSolver
                         .convert(swap.asset, UsdUnits, List.empty)
                         .map(_.toList.map(_.value))
                     )
                   )
      } yield TransactionsInfo(numTxs, volumes.sum / numTxs, volumes.max, UsdUnits).roundAvgValue)
        .getOrElse(TransactionsInfo.empty)

    def getDepositTransactions(window: TimeWindow): F[TransactionsInfo] =
      (for {
        deposits <- OptionT.liftF(orders.getDepositTxs(window) ||> txr.trans)
        numTxs   <- OptionT.fromOption[F](deposits.headOption.map(_.numTxs))
        volumes <- OptionT.liftF(deposits.flatTraverse { deposit =>
                     fiatSolver
                       .convert(deposit.assetX, UsdUnits, List.empty)
                       .flatMap { optX =>
                         fiatSolver
                           .convert(deposit.assetY, UsdUnits, List.empty)
                           .map(optY =>
                             optX
                               .flatMap(eqX => optY.map(eqY => eqX.value + eqY.value))
                               .toList
                           )
                       }
                   })
      } yield TransactionsInfo(numTxs, volumes.sum / numTxs, volumes.max, UsdUnits).roundAvgValue)
        .getOrElse(TransactionsInfo.empty)
  }
}
