package org.ergoplatform.ergo.services.node.models

import derevo.circe.{decoder, encoder}
import derevo.derive
import org.ergoplatform.ergo.TxId
import tofu.logging.derivation.loggable

@derive(encoder, decoder, loggable)
final case class Transaction(
  id: TxId,
  inputs: List[Input],
  outputs: List[Output]
)