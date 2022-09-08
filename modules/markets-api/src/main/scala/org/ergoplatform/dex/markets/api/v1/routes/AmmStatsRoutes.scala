package org.ergoplatform.dex.markets.api.v1.routes

import cats.effect.{Concurrent, ContextShift, Timer}
import cats.syntax.semigroupk._
import org.ergoplatform.common.http.AdaptThrowable.AdaptThrowableEitherT
import org.ergoplatform.common.http.HttpError
import org.ergoplatform.common.http.cache.CacheMiddleware.CachingMiddleware
import org.ergoplatform.common.http.syntax._
import org.ergoplatform.common.models.TimeWindow
import org.ergoplatform.dex.markets.api.v1.endpoints.AmmStatsEndpoints
import org.ergoplatform.dex.markets.api.v1.models.amm.PoolSummary
import org.ergoplatform.dex.markets.api.v1.services.{AmmStats, LqLocks}
import org.ergoplatform.dex.markets.configs.RequestConfig
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

final class AmmStatsRoutes[
  F[_]: Concurrent: ContextShift: Timer: AdaptThrowableEitherT[*[_], HttpError]
](stats: AmmStats[F], locks: LqLocks[F], requestConf: RequestConfig)(implicit
  opts: Http4sServerOptions[F, F]
) {

  private val endpoints = new AmmStatsEndpoints(requestConf)
  import endpoints._

  private val interpreter = Http4sServerInterpreter(opts)

  def routes: HttpRoutes[F] =
    getSwapTxsR <+> getDepositTxsR <+> getPoolLocksR <+> getPlatformStatsR <+> getPoolStatsR <+> getPoolsStatsR <+> getAvgPoolSlippageR <+> getPoolPriceChartR <+> getAmmMarketsR <+> convertToFiatR

  def getSwapTxsR: HttpRoutes[F] =
    interpreter.toRoutes(getSwapTxs)(tw => stats.getSwapTransactions(tw).adaptThrowable.value)

  def getDepositTxsR: HttpRoutes[F] =
    interpreter.toRoutes(getDepositTxs)(tw => stats.getDepositTransactions(tw).adaptThrowable.value)

  def getPoolLocksR: HttpRoutes[F] = interpreter.toRoutes(getPoolLocks) { case (poolId, leastDeadline) =>
    locks.byPool(poolId, leastDeadline).adaptThrowable.value
  }

  def getPoolStatsR: HttpRoutes[F] = interpreter.toRoutes(getPoolStats) { case (poolId, tw) =>
    stats.getPoolSummary(poolId, tw).adaptThrowable.orNotFound(s"PoolStats{poolId=$poolId}").value
  }

  def getPoolsStatsR: HttpRoutes[F] = interpreter.toRoutes(getPoolsStats) { w =>
    stats.getPoolsSummary(w).adaptThrowable.value
  }

  def getPlatformStatsR: HttpRoutes[F] = interpreter.toRoutes(getPlatformStats) { tw =>
    stats.getPlatformSummary(tw).adaptThrowable.value
  }

  def getAvgPoolSlippageR: HttpRoutes[F] = interpreter.toRoutes(getAvgPoolSlippage) { case (poolId, depth) =>
    stats.getAvgPoolSlippage(poolId, depth).adaptThrowable.orNotFound(s"poolId=$poolId").value
  }

  def getPoolPriceChartR: HttpRoutes[F] = interpreter.toRoutes(getPoolPriceChart) { case (poolId, window, res) =>
    stats.getPoolPriceChart(poolId, window, res).adaptThrowable.value
  }

  def getAmmMarketsR: HttpRoutes[F] = interpreter.toRoutes(getAmmMarkets) { tw =>
    stats.getMarkets(tw).adaptThrowable.value
  }

  def convertToFiatR: HttpRoutes[F] = interpreter.toRoutes(convertToFiat) { req =>
    stats.convertToFiat(req.tokenId, req.amount).adaptThrowable.orNotFound(s"Token{id=${req.tokenId}}").value
  }
}

object AmmStatsRoutes {

  def make[
    F[_]: Concurrent: ContextShift: Timer: AdaptThrowableEitherT[*[_], HttpError]
  ](requestConf: RequestConfig)(implicit
    stats: AmmStats[F],
    locks: LqLocks[F],
    opts: Http4sServerOptions[F, F]
  ): HttpRoutes[F] =
    new AmmStatsRoutes[F](stats, locks, requestConf).routes
}
