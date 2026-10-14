package ai.rever.boss.plugin.loader;

/**
 * A contract (ai.rever.boss.plugin.*) owner whose superclass is blocked from the classloader in
 * the gating test: loading it throws NoClassDefFoundError, and a contract owner's linkage
 * failure must still be reported, unlike a third-party one.
 */
public class ContractSubWithBlockedSuper extends com.thirdparty.loaderfixture.SuperThirdParty {
    public int own = 3;
}
