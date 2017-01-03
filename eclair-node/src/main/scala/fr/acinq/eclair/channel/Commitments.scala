package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction}
import fr.acinq.eclair.crypto.LightningCrypto.sha256
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage])
case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]))
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, remotePerCommitmentPoint: Point)
// @formatter:on

/**
  * about remoteNextCommitInfo:
  * we either:
  * - have built and signed their next commit tx with their next revocation hash which can now be discarded
  * - have their next per-commitment point
  * So, when we've signed and sent a commit message and are waiting for their revocation message,
  * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
  */
case class Commitments(localParams: LocalParams, remoteParams: RemoteParams,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localCurrentHtlcId: Long,
                       remoteNextCommitInfo: Either[RemoteCommit, Point],
                       commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaChain, txDb: TxDb, channelId: Long) {
  def anchorId: BinaryData = commitInput.outPoint.txid

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty

  def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)

  def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)
}

object Commitments {
  /**
    * add a change to our proposed change list
    *
    * @param commitments
    * @param proposal
    * @return an updated commitment instance
    */
  private def addLocalProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed :+ proposal))

  private def addRemoteProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed :+ proposal))

  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC): (Commitments, UpdateAddHtlc) = {
    // our available funds as seen by them, including all pending changes
    val reduced = CommitmentSpec.reduce(commitments.remoteCommit.spec, commitments.remoteChanges.acked, commitments.localChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.toRemoteMsat
    if (cmd.amountMsat > available) {
      throw new RuntimeException(s"insufficient funds (available=$available msat)")
    } else {
      val id = cmd.id.getOrElse(commitments.localCurrentHtlcId + 1)
      // TODO: fix routing
      val add: UpdateAddHtlc = UpdateAddHtlc(commitments.channelId, id, cmd.amountMsat, cmd.expiry, cmd.paymentHash, "" /*routing(ByteString.copyFrom(cmd.payment_route.toByteArray))*/)
      val commitments1 = addLocalProposal(commitments, add).copy(localCurrentHtlcId = id)
      (commitments1, add)
    }
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    // their available funds as seen by us, including all pending changes
    val reduced = CommitmentSpec.reduce(commitments.localCommit.spec, commitments.localChanges.acked, commitments.remoteChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.toRemoteMsat
    if (add.amountMsat > available) {
      throw new RuntimeException("Insufficient funds")
    } else {
      // TODO: nodeIds are ignored
      addRemoteProposal(commitments, add)
    }
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC, channelId: Long): (Commitments, UpdateFulfillHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fulfill.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => (addRemoteProposal(commitments, fulfill), htlc)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${fulfill.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}") // TODO: we should fail the channel
    }
  }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, UpdateFailHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u } match {
      case Some(htlc) =>
        val fail: UpdateFailHtlc = ???
        //UpdateFailHtlc(cmd.channelId, cmd.id, fail_reason(ByteString.copyFromUtf8(cmd.reason)))
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fail.id => u.add } match {
      case Some(htlc) => (addRemoteProposal(commitments, fail), htlc)
      case None => throw new RuntimeException(s"unknown htlc id=${fail.id}") // TODO: we should fail the channel
    }
  }

  def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.size > 0 || commitments.localChanges.proposed.size > 0

  def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.size > 0 || commitments.remoteChanges.proposed.size > 0

  def revocationPreimage(seed: BinaryData, index: Long): BinaryData = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: BinaryData, index: Long): BinaryData = Crypto.sha256(revocationPreimage(seed, index))

  def sendCommit(commitments: Commitments): (Commitments, CommitSig) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        throw new RuntimeException("cannot sign when there are no changes")
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(remoteCommit.index, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = Transactions.sign(remoteCommitTx, localParams.fundingPrivkey)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
        val paymentKey = Generators.derivePrivKey(localParams.paymentSecret, remoteNextPerCommitmentPoint)
        val htlcSigs = sortedHtlcTxs.map(Transactions.sign(_, paymentKey))

        // don't sign if they don't get paid
        val commitSig = CommitSig(
          channelId = commitments.channelId,
          signature = sig,
          htlcSignatures = htlcSigs.toList
        )
        val commitments1 = commitments.copy(
          remoteNextCommitInfo = Left(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil))
        (commitments1, commitSig)
      case Left(_) =>
        throw new RuntimeException("cannot sign until next revocation hash is received")
    }
  }

  def receiveCommit(commitments: Commitments, commit: CommitSig): (Commitments, RevokeAndAck) = {
    import commitments._
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by * them * to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    if (!remoteHasChanges(commitments))
      throw new RuntimeException("cannot sign when there are no changes")

    // check that their signature is valid
    // signatures are now optional in the commit message, and will be sent only if the other party is actually
    // receiving money i.e its commit tx has one output for them

    val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt + 1)
    // TODO: Long or Int??
    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(localCommit.index, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
    val sig = Transactions.sign(localCommitTx, localParams.fundingPrivkey)

    // TODO: should we have optional sig? (original comment: this tx will NOT be signed if our output is empty)

    // no need to compute htlc sigs if commit sig doesn't check out
    val signedCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivkey.toPoint, remoteParams.fundingPubkey, sig, commit.signature)
    if (Transactions.checkSig(signedCommitTx).isFailure) throw new RuntimeException("invalid sig")

    val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    require(commit.htlcSignatures.size == sortedHtlcTxs.size, s"htlc sig count mismatch (received=${commit.htlcSignatures.size}, expected=${sortedHtlcTxs.size})")
    val paymentKey = Generators.derivePrivKey(localParams.paymentSecret, localPerCommitmentPoint)
    val signedHtlcTxsAndSigs = sortedHtlcTxs.zipWithIndex.collect {
      case (htlcTx: HtlcTimeoutTx, index) =>
        val htlcSig = Transactions.sign(htlcTx, paymentKey)
        val signedHtlcTx = Transactions.addSigs(htlcTx, htlcSig, commit.htlcSignatures(index))
        if (Transactions.checkSig(signedHtlcTx).isFailure) throw new RuntimeException("invalid sig")
        (signedHtlcTx, htlcSig)
      case (htlcTx: HtlcSuccessTx, index) =>
        val htlcSig = Transactions.sign(htlcTx, paymentKey)
        // TODO: bad sig for now, should test with pubkey
        val paymentPreimage: BinaryData = BinaryData("00" * 32)
        val signedHtlcTx = Transactions.addSigs(htlcTx, htlcSig, commit.htlcSignatures(index), paymentPreimage)
        //if (Transactions.checkSig(signedHtlcTx).isFailure) throw new RuntimeException("invalid sig")
        (signedHtlcTx, htlcSig)
    }
    val timeoutHtlcSigs = signedHtlcTxsAndSigs.collect {
      case (_: HtlcTimeoutTx, sig) => sig
    }
    val signedHtlcTimeoutTxs = signedHtlcTxsAndSigs.collect {
      case (htlcTx: HtlcTimeoutTx, _) => htlcTx
    }
    val signedHtlcSuccessTxs = signedHtlcTxsAndSigs.collect {
      case (htlcTx: HtlcSuccessTx, _) => htlcTx
    }

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = Generators.perCommitSecret(localParams.shaSeed, commitments.localCommit.index.toInt)
    // TODO: Long or Int??
    val localNextPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt + 2)
    // TODO: Long or Int??
    val revocation = RevokeAndAck(
      channelId = commitments.channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint,
      htlcTimeoutSignatures = timeoutHtlcSigs.toList
    )

    // update our commitment data
    val ourCommit1 = localCommit.copy(index = localCommit.index + 1, spec, publishableTxs = (signedCommitTx, signedHtlcTimeoutTxs, signedHtlcSuccessTxs))
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = ourCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Commitments = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(theirNextCommit) if Scalar(revocation.perCommitmentSecret).toPoint != remoteCommit.remotePerCommitmentPoint =>
        throw new RuntimeException("invalid preimage")
      case Left(theirNextCommit) =>
        // this is their revoked commit tx

        // TODO: check their HTLC-Timeout sigs are valid and store their sig

        // TODO: add
        val (remoteCommitTx, htlcTimeoutCommitTx, htlcSuccessCommitTx) = makeRemoteTxs(localCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
        //val punishTx: Transaction = ??? //Helpers.claimRevokedCommitTx(theirTxTemplate, revocation.revocationPreimage, localParams.finalPrivKey)
        //Transaction.correctlySpends(punishTx, Seq(theirTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        //txDb.add(theirTx.txid, punishTx)

        commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFFFFFL - commitments.remoteCommit.index))
      case Right(_) =>
        throw new RuntimeException("received unexpected RevokeAndAck message")
    }
  }

  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, localPerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.delayedPaymentKey.toPoint, localPerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(localParams.revocationSecret.toPoint, localPerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentSecret.toPoint, remoteParams.paymentBasepoint, localParams.isFunder, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, localParams.toSelfDelay, localPubkey, remotePubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, localParams.toSelfDelay, localPubkey, remotePubkey, spec)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, remotePerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.paymentSecret.toPoint, remotePerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, remotePerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localParams.paymentSecret.toPoint, !localParams.isFunder, Satoshi(remoteParams.dustLimitSatoshis), remoteRevocationPubkey, remoteParams.toSelfDelay, remotePubkey, localPubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), remoteRevocationPubkey, remoteParams.toSelfDelay, remotePubkey, localPubkey, spec)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }
}


