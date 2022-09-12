package org.ergoplatform.dex.markets.repositories

import cats.tagless.syntax.functorK._
import cats.{FlatMap, Functor}
import derevo.derive
import doobie.ConnectionIO
import doobie.util.query.Query0
import org.ergoplatform.common.models.TimeWindow
import org.ergoplatform.dex.domain.amm.PoolId
import org.ergoplatform.dex.markets.api.v1.models.charts.ChartGap
import org.ergoplatform.dex.markets.db.models.amm._
import org.ergoplatform.dex.markets.db.sql.AnalyticsSql
import org.ergoplatform.ergo.TokenId
import tofu.doobie.LiftConnectionIO
import tofu.doobie.log.EmbeddableLogHandler
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._
import tofu.syntax.monadic._

@derive(representableK)
trait Pools[F[_]] {

  /** Get general info about the pool with the given `id`.
    */
  def info(id: PoolId): F[Option[PoolInfo]]

  /** Get snapshots of all pools.
    */
  def snapshots: F[List[PoolSnapshot]]

  /** Get snapshots of those pools that involve the given asset.
    */
  def snapshotsByAsset(asset: TokenId): F[List[PoolSnapshot]]

  /** Get a snapshot of the pool with the given `id`.
    */
  def snapshot(id: PoolId): F[Option[PoolSnapshot]]

  /** Get recent volumes by all pools.
    */
  def volumes(window: TimeWindow): F[List[PoolVolumeSnapshot]]

  /** Get volumes by a given pool.
    */
  def volume(id: PoolId, window: TimeWindow): F[Option[PoolVolumeSnapshot]]

  /** Get fees by all pools.
    */
  def fees(window: TimeWindow): F[List[PoolFeesSnapshot]]

  /** Get fees by a given pool.
    */
  def fees(id: PoolId, window: TimeWindow): F[Option[PoolFeesSnapshot]]

  /** Get snapshots of a given pool within given depth.
    */
  def trace(id: PoolId, depth: Int, currHeight: Int): F[List[PoolTrace]]

  /** Get most recent snapshot of a given pool below given depth.
    */
  def prevTrace(id: PoolId, depth: Int, currHeight: Int): F[Option[PoolTrace]]

  /** Get average asset amounts in a given pool within given height window.
    */
  def avgAmounts(id: PoolId, window: TimeWindow, resolution: Int): F[List[AvgAssetAmounts]]

  /** Get full asset info by id.
   */
  def assetById(id: TokenId): F[Option[AssetInfo]]

  def getChartsByGap(gap: ChartGap, poolId: PoolId, from: Long, to: Long): F[List[AvgAssetAmountsWithPrev]]

  def getLatestChartByGap(gap: ChartGap, poolId: PoolId): F[Option[AvgAssetAmount]]
}

object Pools {

  def make[I[_]: Functor, D[_]: FlatMap: LiftConnectionIO](implicit
    elh: EmbeddableLogHandler[D],
    logs: Logs[I, D]
  ): I[Pools[D]] =
    logs.forService[Pools[D]].map { implicit l =>
      elh.embed(implicit lh => new PoolsTracing[D] attach new Live(new AnalyticsSql()).mapK(LiftConnectionIO[D].liftF))
    }

  final class Live(sql: AnalyticsSql) extends Pools[ConnectionIO] {

    def info(id: PoolId): ConnectionIO[Option[PoolInfo]] =
      sql.getInfo(id).option

    def snapshots: ConnectionIO[List[PoolSnapshot]] =
      sql.getPoolSnapshots.to[List]

    def snapshotsByAsset(asset: TokenId): ConnectionIO[List[PoolSnapshot]] =
      sql.getPoolSnapshotsByAsset(asset).to[List]

    def snapshot(id: PoolId): ConnectionIO[Option[PoolSnapshot]] =
      sql.getPoolSnapshot(id).option

    def volumes(window: TimeWindow): ConnectionIO[List[PoolVolumeSnapshot]] =
      sql.getPoolVolumes(window).to[List]

    def volume(id: PoolId, window: TimeWindow): ConnectionIO[Option[PoolVolumeSnapshot]] =
      sql.getPoolVolumes(id, window).option

    def fees(window: TimeWindow): ConnectionIO[List[PoolFeesSnapshot]] =
      sql.getPoolFees(window).to[List]

    def fees(id: PoolId, window: TimeWindow): ConnectionIO[Option[PoolFeesSnapshot]] =
      sql.getPoolFees(id, window).option

    def trace(id: PoolId, depth: Int, currHeight: Int): ConnectionIO[List[PoolTrace]] =
      sql.getPoolTrace(id, depth, currHeight).to[List]

    def prevTrace(id: PoolId, depth: Int, currHeight: Int): ConnectionIO[Option[PoolTrace]] =
      sql.getPrevPoolTrace(id, depth, currHeight).option

    def avgAmounts(id: PoolId, window: TimeWindow, resolution: Int): ConnectionIO[List[AvgAssetAmounts]] =
      sql.getAvgPoolSnapshot(id, window, resolution).to[List]

    def assetById(id: TokenId): ConnectionIO[Option[AssetInfo]] =
      sql.getAssetById(id).option

    def getChartsByGap(gap: ChartGap, poolId: PoolId, from: Long, to: Long): ConnectionIO[List[AvgAssetAmountsWithPrev]] =
      sql.getChartsByGap(gap, poolId, from, to).to[List]

    def getLatestChartByGap(gap: ChartGap, poolId: PoolId): ConnectionIO[Option[AvgAssetAmount]] =
      sql.getLatestChartByGap(gap, poolId).option
  }

  final class PoolsTracing[F[_]: FlatMap: Logging] extends Pools[Mid[F, *]] {

    def info(poolId: PoolId): Mid[F, Option[PoolInfo]] =
      for {
        _ <- trace"info(poolId=$poolId)"
        r <- _
        _ <- trace"info(poolId=$poolId) -> ${r.size} info entities selected"
      } yield r

    def snapshots: Mid[F, List[PoolSnapshot]] =
      for {
        _ <- trace"snapshots"
        r <- _
        _ <- trace"snapshots -> ${r.size} snapshots selected"
      } yield r

    def snapshotsByAsset(asset: TokenId): Mid[F, List[PoolSnapshot]] =
      for {
        _ <- trace"snapshotsByAsset(asset=$asset)"
        r <- _
        _ <- trace"snapshotsByAsset(asset=$asset) -> ${r.size} snapshots selected"
      } yield r

    def snapshot(poolId: PoolId): Mid[F, Option[PoolSnapshot]] =
      for {
        _ <- trace"snapshot(poolId=$poolId)"
        r <- _
        _ <- trace"snapshot(poolId=$poolId) -> ${r.size} snapshots selected"
      } yield r

    def volumes(window: TimeWindow): Mid[F, List[PoolVolumeSnapshot]] =
      for {
        _ <- trace"volumes(window=$window)"
        r <- _
        _ <- trace"volumes(window=$window) -> ${r.size} volume snapshots selected"
      } yield r

    def volume(poolId: PoolId, window: TimeWindow): Mid[F, Option[PoolVolumeSnapshot]] =
      for {
        _ <- trace"volume(poolId=$poolId, window=$window)"
        r <- _
        _ <- trace"volume(poolId=$poolId, window=$window) -> ${r.size} volume snapshots selected"
      } yield r

    def fees(window: TimeWindow): Mid[F, List[PoolFeesSnapshot]] =
      for {
        _ <- trace"fees(window=$window)"
        r <- _
        _ <- trace"fees(window=$window) -> ${r.size} fees snapshots selected"
      } yield r

    def fees(poolId: PoolId, window: TimeWindow): Mid[F, Option[PoolFeesSnapshot]] =
      for {
        _ <- trace"fees(poolId=$poolId, window=$window)"
        r <- _
        _ <- trace"fees(poolId=$poolId, window=$window) -> ${r.size} fees snapshots selected"
      } yield r

    def trace(id: PoolId, depth: Int, currHeight: Int): Mid[F, List[PoolTrace]] =
      for {
        _ <- trace"trace(poolId=$id, depth=$depth, currHeight=$currHeight)"
        r <- _
        _ <- trace"trace(poolId=$id, depth=$depth, currHeight=$currHeight) -> ${r.size} trace snapshots selected"
      } yield r

    def prevTrace(id: PoolId, depth: Int, currHeight: Int): Mid[F, Option[PoolTrace]] =
      for {
        _ <- trace"trace(poolId=$id, depth=$depth, currHeight=$currHeight)"
        r <- _
        _ <- trace"trace(poolId=$id, depth=$depth, currHeight=$currHeight) -> ${r.size} trace snapshots selected"
      } yield r

    def avgAmounts(id: PoolId, window: TimeWindow, resolution: Int): Mid[F, List[AvgAssetAmounts]] =
      for {
        _ <- trace"trace(poolId=$id, window=$window, resolution=$resolution)"
        r <- _
        _ <- trace"trace(poolId=$id, window=$window, resolution=$resolution) -> ${r.size} trace snapshots selected"
      } yield r

    def assetById(id: TokenId): Mid[F, Option[AssetInfo]] =
      for {
        _ <- trace"assetById(id=$id)"
        r <- _
        _ <- trace"assetById(id=$id) -> ${r.size} assets selected"
      } yield r

    def getChartsByGap(gap: ChartGap, poolId: PoolId, from: Long, to: Long): Mid[F, List[AvgAssetAmountsWithPrev]] =
      for {
        _ <- trace"getChartsByGap($gap, $poolId, $from, $to)"
        r <- _
        _ <- trace"getChartsByGap($gap, $poolId, $from, $to) -> ${r.size} points selected"
      } yield r

    def getLatestChartByGap(gap: ChartGap, poolId: PoolId): Mid[F, Option[AvgAssetAmount]] =
      for {
        _ <- trace"getLatestChartByGap($gap, $poolId)"
        r <- _
        _ <- trace"getLatestChartByGap($gap, $poolId) -> ${r.map(_.timestamp)} point selected."
      } yield r
  }
}
