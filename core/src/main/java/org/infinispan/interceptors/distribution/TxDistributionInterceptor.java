package org.infinispan.interceptors.distribution;

import org.infinispan.atomic.DeltaCompositeKey;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.MVCCEntry;
import org.infinispan.metadata.Metadata;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.util.InfinispanCollections;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.LocalTxInvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.L1Manager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.jgroups.SuspectException;
import org.infinispan.transaction.LocalTransaction;
import org.infinispan.transaction.LockingMode;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Handles the distribution of the transactional caches.
 *
 * @author Mircea Markus
 * @since 5.2
 */
public class TxDistributionInterceptor extends BaseDistributionInterceptor {

   private static Log log = LogFactory.getLog(TxDistributionInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   private boolean isPessimisticCache;
   private boolean useClusteredWriteSkewCheck;

   private L1Manager l1Manager;
   private boolean isL1CacheEnabled;

   private static final RecipientGenerator CLEAR_COMMAND_GENERATOR = new RecipientGenerator() {
      @Override
      public List<Address> generateRecipients() {
         return null;
      }

      @Override
      public Collection<Object> getKeys() {
         return InfinispanCollections.emptySet();
      }
   };

   @Inject
   public void injectDependencies(L1Manager l1Manager) {
      this.l1Manager = l1Manager;
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      try {
         return handleTxWriteCommand(ctx, command, new SingleKeyRecipientGenerator(command.getKey()), false);
      } finally {
         boolean ignorePreviousValues = ignorePreviousValueOnBackup(command, ctx);
         command.setIgnorePreviousValue(ignorePreviousValues);
      }
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      try {
         return handleTxWriteCommand(ctx, command, new SingleKeyRecipientGenerator(command.getKey()), false);
      } finally {
         boolean ignorePreviousValues = ignorePreviousValueOnBackup(command, ctx);
         command.setIgnorePreviousValue(ignorePreviousValues);
      }
   }

   @Start
   public void start() {
      isPessimisticCache = cacheConfiguration.transaction().lockingMode() == LockingMode.PESSIMISTIC;
      isL1CacheEnabled = cacheConfiguration.clustering().l1().enabled();
      useClusteredWriteSkewCheck = !isPessimisticCache &&
            cacheConfiguration.versioning().enabled() && cacheConfiguration.locking().writeSkewCheck();
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      if (command.hasFlag(Flag.PUT_FOR_EXTERNAL_READ)) {
         return handleNonTxWriteCommand(ctx, command);
      }

      SingleKeyRecipientGenerator skrg = new SingleKeyRecipientGenerator(command.getKey());
      Object returnValue = handleTxWriteCommand(ctx, command, skrg, command.hasFlag(Flag.PUT_FOR_STATE_TRANSFER));
      if (ignorePreviousValueOnBackup(command, ctx)) {
         command.setIgnorePreviousValue(true);
         command.setPutIfAbsent(false);
      } else {
         command.setIgnorePreviousValue(false);
      }
      // If this was a remote put record that which sent it
      if (isL1CacheEnabled && !ctx.isOriginLocal() && !skrg.generateRecipients().contains(ctx.getOrigin()))
         l1Manager.addRequestor(command.getKey(), ctx.getOrigin());

      return returnValue;
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      // don't bother with a remote get for the PutMapCommand!
      return handleTxWriteCommand(ctx, command, new MultipleKeysRecipientGenerator(command.getMap().keySet()), true);
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      return handleTxWriteCommand(ctx, command, CLEAR_COMMAND_GENERATOR, false);
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      return visitGetCommand(ctx, command, false);
   }

   private Object visitGetCommand(InvocationContext ctx, GetKeyValueCommand command,
         boolean isGetCacheEntry) throws Throwable {
      try {
         Object returnValue = invokeNextInterceptor(ctx, command);
         // If L1 caching is enabled, this is a remote command, and we found a value in our cache
         // we store it so that we can later invalidate it
         if (returnValue != null && isL1CacheEnabled && !ctx.isOriginLocal())
            l1Manager.addRequestor(command.getKey(), ctx.getOrigin());

         //if the cache entry has the value lock flag set, skip the remote get.
         CacheEntry entry = ctx.lookupEntry(command.getKey());
         boolean skipRemoteGet = entry != null && entry.skipRemoteGet();

         // need to check in the context as well since a null retval is not necessarily an indication of the entry not being
         // available.  It could just have been removed in the same tx beforehand.  Also don't bother with a remote get if
         // the entry is mapped to the local node.
         if (!skipRemoteGet && returnValue == null && ctx.isOriginLocal()) {
            Object key = command.getKey();
            if (needsRemoteGet(ctx, command)) {
               returnValue = remoteGetAndStoreInL1(ctx, key, false, command);
            }
            if (returnValue == null && !ctx.isEntryRemovedInContext(command.getKey())) {
               returnValue = localGet(ctx, key, false, command, isGetCacheEntry);
            }
         }
         return returnValue;
      } catch (SuspectException e) {
         // retry
         return visitGetKeyValueCommand(ctx, command);
      }
   }

   protected void lockAndWrap(InvocationContext ctx, Object key, InternalCacheEntry ice, FlagAffectedCommand command) throws InterruptedException {
      boolean skipLocking = hasSkipLocking(command);
      long lockTimeout = getLockAcquisitionTimeout(command, skipLocking);
      lockManager.acquireLock(ctx, key, lockTimeout, skipLocking);
      entryFactory.wrapEntryForPut(ctx, key, ice, false, command, false);
   }

   @Override
   public Object visitLockControlCommand(TxInvocationContext ctx, LockControlCommand command) throws Throwable {
      if (ctx.isOriginLocal()) {
         //In Pessimistic mode, the delta composite keys were sent to the wrong owner and never locked.
         ArrayList<Object> keyToCheckOwners = new ArrayList<Object>(command.getKeys().size());
         for (Object key : command.getKeys()) {
            if (key instanceof DeltaCompositeKey) {
               keyToCheckOwners.add(((DeltaCompositeKey) key).getDeltaAwareValueKey());
            } else {
               keyToCheckOwners.add(key);
            }
         }
         final Collection<Address> affectedNodes = cdl.getOwners(keyToCheckOwners);
         ((LocalTxInvocationContext) ctx).remoteLocksAcquired(affectedNodes == null ? dm.getConsistentHash().getMembers() : affectedNodes);
         log.tracef("Registered remote locks acquired %s", affectedNodes);
         rpcManager.invokeRemotely(affectedNodes, command, rpcManager.getDefaultRpcOptions(true, false));
      }
      return invokeNextInterceptor(ctx, command);
   }

   // ---- TX boundary commands
   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      if (shouldInvokeRemoteTxCommand(ctx)) {
         Future<?> f = flushL1Caches(ctx);
         sendCommitCommand(ctx, command);
         blockOnL1FutureIfNeeded(f);

      } else if (isL1CacheEnabled && !ctx.isOriginLocal() && !ctx.getLockedKeys().isEmpty()) {
         // We fall into this block if we are a remote node, happen to be the primary data owner and have locked keys.
         // it is still our responsibility to invalidate L1 caches in the cluster.
         blockOnL1FutureIfNeeded(flushL1Caches(ctx));
      }
      return invokeNextInterceptor(ctx, command);
   }

   private void blockOnL1FutureIfNeeded(Future<?> f) {
      if (f != null && cacheConfiguration.transaction().syncCommitPhase()) {
         try {
            f.get();
         } catch (Exception e) {
            // Ignore SuspectExceptions - if the node has gone away then there is nothing to invalidate anyway.
            if (!(e.getCause() instanceof SuspectException)) {
               getLog().failedInvalidatingRemoteCache(e);
            }
         }
      }
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      Object retVal = invokeNextInterceptor(ctx, command);

      if (shouldInvokeRemoteTxCommand(ctx)) {
         if (command.isOnePhaseCommit()) flushL1Caches(ctx); // if we are one-phase, don't block on this future.

         boolean affectsAllNodes = ctx.getCacheTransaction().hasModification(ClearCommand.class);
         Collection<Address> recipients = affectsAllNodes ? dm.getWriteConsistentHash().getMembers() : cdl.getOwners(ctx.getAffectedKeys());
         recipients = recipients == null ? dm.getWriteConsistentHash().getMembers() : recipients;
         prepareOnAffectedNodes(ctx, command, recipients, defaultSynchronous);

         ((LocalTxInvocationContext) ctx).remoteLocksAcquired(recipients == null ? dm.getWriteConsistentHash().getMembers() : recipients);
      } else if (isL1CacheEnabled && command.isOnePhaseCommit() && !ctx.isOriginLocal() && !ctx.getLockedKeys().isEmpty()) {
         // We fall into this block if we are a remote node, happen to be the primary data owner and have locked keys.
         // it is still our responsibility to invalidate L1 caches in the cluster.
         flushL1Caches(ctx);
      }
      return retVal;
   }

   protected void prepareOnAffectedNodes(TxInvocationContext ctx, PrepareCommand command, Collection<Address> recipients, boolean sync) {
      try {
         // this method will return immediately if we're the only member (because exclude_self=true)
         RpcOptions rpcOptions;
         if (sync && command.isOnePhaseCommit()) {
            rpcOptions = rpcManager.getRpcOptionsBuilder(ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS, false).build();
         } else {
            rpcOptions = rpcManager.getDefaultRpcOptions(sync);
         }
         rpcManager.invokeRemotely(recipients, command, rpcOptions);
      } finally {
         transactionRemotelyPrepared(ctx);
      }
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command) throws Throwable {
      if (shouldInvokeRemoteTxCommand(ctx)) {
         rpcManager.invokeRemotely(getCommitNodes(ctx), command, rpcManager.getDefaultRpcOptions(
               cacheConfiguration.transaction().syncRollbackPhase(), false));
      }

      return invokeNextInterceptor(ctx, command);
   }

   private Collection<Address> getCommitNodes(TxInvocationContext ctx) {
      LocalTransaction localTx = (LocalTransaction) ctx.getCacheTransaction();
      Collection<Address> affectedNodes = cdl.getOwners(ctx.getAffectedKeys());
      List<Address> members = dm.getConsistentHash().getMembers();
      return localTx.getCommitNodes(affectedNodes, rpcManager.getTopologyId(), members);
   }

   private void sendCommitCommand(TxInvocationContext ctx, CommitCommand command) throws TimeoutException, InterruptedException {
      Collection<Address> recipients = getCommitNodes(ctx);
      boolean syncCommitPhase = cacheConfiguration.transaction().syncCommitPhase();
      RpcOptions rpcOptions;
      if (syncCommitPhase) {
         rpcOptions = rpcManager.getRpcOptionsBuilder(ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS, false  ).build();
      } else {
         rpcOptions = rpcManager.getDefaultRpcOptions(false, false);
      }
      rpcManager.invokeRemotely(recipients, command, rpcOptions);
   }

   private boolean shouldFetchRemoteValuesForWriteSkewCheck(InvocationContext ctx, WriteCommand cmd) {
      if (useClusteredWriteSkewCheck && ctx.isInTxScope() && dm.isRehashInProgress()) {
         for (Object key : cmd.getAffectedKeys()) {
            if (dm.isAffectedByRehash(key) && !dataContainer.containsKey(key)) return true;
         }
      }
      return false;
   }

   /**
    * If we are within one transaction we won't do any replication as replication would only be performed at commit
    * time. If the operation didn't originate locally we won't do any replication either.
    */
   private Object handleTxWriteCommand(InvocationContext ctx, WriteCommand command, RecipientGenerator recipientGenerator, boolean skipRemoteGet) throws Throwable {
      // see if we need to load values from remote sources first
      if (ctx.isOriginLocal() && !skipRemoteGet || command.isConditional() || shouldFetchRemoteValuesForWriteSkewCheck(ctx, command))
         remoteGetBeforeWrite(ctx, command, recipientGenerator);

      // FIRST pass this call up the chain.  Only if it succeeds (no exceptions) locally do we attempt to distribute.
      return invokeNextInterceptor(ctx, command);
   }

   private Object localGet(InvocationContext ctx, Object key, boolean isWrite,
         FlagAffectedCommand command, boolean isGetCacheEntry) throws Throwable {
      InternalCacheEntry ice = dataContainer.get(key);
      if (ice != null) {
         if (isWrite && isPessimisticCache && ctx.isInTxScope()) {
            ((TxInvocationContext) ctx).addAffectedKey(key);
         }
         if (!ctx.replaceValue(key, ice)) {
            if (isWrite)
               lockAndWrap(ctx, key, ice, command);
            else
               ctx.putLookedUpEntry(key, ice);
         }
         return isGetCacheEntry ? ice : ice.getValue();
      }
      return null;
   }

   protected void remoteGetBeforeWrite(InvocationContext ctx, WriteCommand command, RecipientGenerator keygen) throws Throwable {
      // this should only happen if:
      //   a) unsafeUnreliableReturnValues is false
      //   b) unsafeUnreliableReturnValues is true, we are in a TX and the command is conditional
      if (isNeedReliableReturnValues(command) || command.isConditional() || shouldFetchRemoteValuesForWriteSkewCheck(ctx, command)) {
         for (Object k : keygen.getKeys()) {
            CacheEntry entry = ctx.lookupEntry(k);
            boolean skipRemoteGet =  entry != null && entry.skipRemoteGet();
            if (skipRemoteGet) {
               continue;
            }
            Object returnValue = remoteGetAndStoreInL1(ctx, k, true, command);
            if (returnValue == null) {
               localGet(ctx, k, true, command, false);
            }
         }
      }
   }

   private boolean isNotInL1(Object key) {
      return !isL1CacheEnabled || !dataContainer.containsKey(key);
   }

   private Object remoteGetAndStoreInL1(InvocationContext ctx, Object key, boolean isWrite, FlagAffectedCommand command) throws Throwable {
      boolean isKeyLocalToNode = dm.getReadConsistentHash().isKeyLocalToNode(rpcManager.getAddress(), key);

      if (ctx.isOriginLocal() && !isKeyLocalToNode && isNotInL1(key) || dm.isAffectedByRehash(key) && !dataContainer.containsKey(key)) {
         if (trace) log.tracef("Doing a remote get for key %s", key);

         boolean acquireRemoteLock = false;
         if (ctx.isInTxScope()) {
            TxInvocationContext txContext = (TxInvocationContext) ctx;
            acquireRemoteLock = isWrite && isPessimisticCache && !txContext.getAffectedKeys().contains(key);
         }
         // attempt a remote lookup
         InternalCacheEntry ice = retrieveFromRemoteSource(key, ctx, acquireRemoteLock, command, isWrite);

         if (acquireRemoteLock) {
            ((TxInvocationContext) ctx).addAffectedKey(key);
         }

         if (ice != null) {
            if (useClusteredWriteSkewCheck && ctx.isInTxScope()) {
               ((TxInvocationContext)ctx).getCacheTransaction().putLookedUpRemoteVersion(key, ice.getMetadata().version());
            }

            if (isL1CacheEnabled) {
               // We've requested the key only from the owners current (read) CH.
               // If the intersection of owners in the current and pending CHs is empty,
               // the requestor information might be lost, so we shouldn't store the entry in L1.
               if (dm.isAffectedByRehash(key)) {
                  if (trace) log.tracef("State transfer in progress for key %s, not storing to L1");
                  return ice.getValue();
               }

               if (trace) log.tracef("Caching remotely retrieved entry for key %s in L1", key);
               // This should be fail-safe
               try {
                  long l1Lifespan = cacheConfiguration.clustering().l1().lifespan();
                  long lifespan = ice.getLifespan() < 0 ? l1Lifespan : Math.min(ice.getLifespan(), l1Lifespan);
                  // Make a copy of the metadata stored internally, adjust
                  // lifespan/maxIdle settings and send them a modification
                  Metadata newMetadata = ice.getMetadata().builder()
                        .lifespan(lifespan).maxIdle(-1).build();
                  PutKeyValueCommand put = cf.buildPutKeyValueCommand(
                        ice.getKey(), ice.getValue(), newMetadata, command.getFlags());
                  ctx.replaceValue(key, ice);
                  lockAndWrap(ctx, key, ice, command);
                  invokeNextInterceptor(ctx, put);
               } catch (Exception e) {
                  // Couldn't store in L1 for some reason.  But don't fail the transaction!
                  log.infof("Unable to store entry %s in L1 cache", key);
                  log.debug("Inability to store in L1 caused by", e);
               }
            } else {
               if (!ctx.replaceValue(key, ice)) {
                  if (isWrite)
                     lockAndWrap(ctx, key, ice, command);
                  else {
                     ctx.putLookedUpEntry(key, ice);
                     if (ctx.isInTxScope()) {
                        ((TxInvocationContext) ctx).getCacheTransaction().replaceVersionRead(key, ice.getMetadata().version());
                     }
                  }
               }
            }
            return ice.getValue();
         }
      } else {
         if (trace) log.tracef("Not doing a remote get for key %s since entry is mapped to current node (%s), or is in L1.  Owners are %s", key, rpcManager.getAddress(), dm.locate(key));
      }
      return null;
   }

   private Future<?> flushL1Caches(InvocationContext ctx) {
      // TODO how do we tell the L1 manager which keys are removed and which keys may still exist in remote L1?
      return isL1CacheEnabled ? l1Manager.flushCacheWithSimpleFuture(ctx.getLockedKeys(), null, ctx.getOrigin(), true) : null;
   }
}
