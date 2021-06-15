package org.ergoplatform.dex.domain.amm.state

import derevo.derive
import tofu.logging.derivation.loggable

/** On-chain entity state.
  */
@derive(loggable)
final case class OnChain[T](entity: T)