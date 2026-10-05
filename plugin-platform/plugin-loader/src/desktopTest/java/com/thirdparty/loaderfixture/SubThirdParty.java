package com.thirdparty.loaderfixture;

/**
 * A non-contract (third-party-shaped) owner for the LinkageError gating test: when its
 * superclass (SuperThirdParty) is blocked from the classloader, loading it throws
 * NoClassDefFoundError - the bundled-library scenario the validator must not hard-fail.
 */
public class SubThirdParty extends SuperThirdParty {
    public int own = 2;
}
