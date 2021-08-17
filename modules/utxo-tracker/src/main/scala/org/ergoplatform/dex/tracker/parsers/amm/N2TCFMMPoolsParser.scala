package org.ergoplatform.dex.tracker.parsers.amm

import org.ergoplatform.dex.domain.amm.state.Confirmed
import org.ergoplatform.dex.domain.amm.{CFMMPool, PoolId}
import org.ergoplatform.dex.domain.{AssetAmount, BoxInfo}
import org.ergoplatform.dex.protocol.amm.AMMType.N2T_CFMM
import org.ergoplatform.dex.protocol.amm.constants
import org.ergoplatform.ergo.models.SConstant.IntConstant
import org.ergoplatform.ergo.models.{Output, RegisterId}

object N2TCFMMPoolsParser extends CFMMPoolsParser[N2T_CFMM] {

  def pool(box: Output): Option[Confirmed[CFMMPool]] =
    for {
      nft <- box.assets.lift(constants.cfmm.t2t.IndexNFT)
      lp  <- box.assets.lift(constants.cfmm.t2t.IndexLP)
      x   <- box.assets.lift(constants.cfmm.t2t.IndexX)
      y   <- box.assets.lift(constants.cfmm.t2t.IndexY)
      fee <- box.additionalRegisters.get(RegisterId.R4).collect { case IntConstant(x) => x }
    } yield Confirmed(
      CFMMPool(
        PoolId(nft.tokenId),
        AssetAmount.fromBoxAsset(lp),
        AssetAmount.fromBoxAsset(x),
        AssetAmount.fromBoxAsset(y),
        fee,
        BoxInfo.fromBox(box)
      )
    )
}
