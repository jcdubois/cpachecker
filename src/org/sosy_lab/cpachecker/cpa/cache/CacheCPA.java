package org.sosy_lab.cpachecker.cpa.cache;

import java.util.HashMap;
import java.util.Map;

import org.sosy_lab.cpachecker.cfa.objectmodel.CFAFunctionDefinitionNode;
import org.sosy_lab.cpachecker.core.interfaces.AbstractDomain;
import org.sosy_lab.cpachecker.core.interfaces.AbstractElement;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.MergeOperator;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.PrecisionAdjustment;
import org.sosy_lab.cpachecker.core.interfaces.StopOperator;
import org.sosy_lab.cpachecker.core.interfaces.TransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.WrapperCPA;

/*
 * CAUTION: The cache for precision adjustment is only correct for CPAs that do 
 * _NOT_ depend on the reached set when performing prec.
 * 
 */
public class CacheCPA implements ConfigurableProgramAnalysis, WrapperCPA {

  private final ConfigurableProgramAnalysis mCachedCPA;
  private final Map<CFAFunctionDefinitionNode, AbstractElement> mInitialElementsCache;
  private final Map<CFAFunctionDefinitionNode, Precision> mInitialPrecisionsCache;
  private final CacheTransferRelation mCacheTransferRelation;
  private final CachePrecisionAdjustment mCachePrecisionAdjustment;
  private final CacheMergeOperator mCacheMergeOperator;
  
  public CacheCPA(ConfigurableProgramAnalysis pCachedCPA) {
    mCachedCPA = pCachedCPA;
    mInitialElementsCache = new HashMap<CFAFunctionDefinitionNode, AbstractElement>();
    mInitialPrecisionsCache = new HashMap<CFAFunctionDefinitionNode, Precision>();
    mCacheTransferRelation = new CacheTransferRelation(mCachedCPA.getTransferRelation());
    mCachePrecisionAdjustment = new CachePrecisionAdjustment(mCachedCPA.getPrecisionAdjustment());
    mCacheMergeOperator = new CacheMergeOperator(mCachedCPA.getMergeOperator());
  }
  
  @Override
  public AbstractDomain getAbstractDomain() {
    return mCachedCPA.getAbstractDomain();
  }

  @Override
  public TransferRelation getTransferRelation() {
    return mCacheTransferRelation;
  }

  @Override
  public MergeOperator getMergeOperator() {
    return mCacheMergeOperator;
  }

  @Override
  public StopOperator getStopOperator() {
    return mCachedCPA.getStopOperator();
  }

  @Override
  public PrecisionAdjustment getPrecisionAdjustment() {
    return mCachePrecisionAdjustment;
  }

  @Override
  public AbstractElement getInitialElement(CFAFunctionDefinitionNode pNode) {
    AbstractElement lInitialElement = mInitialElementsCache.get(pNode);
    
    if (lInitialElement == null) {
      lInitialElement = mCachedCPA.getInitialElement(pNode);
      mInitialElementsCache.put(pNode, lInitialElement);
    }
    
    return lInitialElement;
  }

  @Override
  public Precision getInitialPrecision(CFAFunctionDefinitionNode pNode) {
    Precision lInitialPrecision = mInitialPrecisionsCache.get(pNode);
    
    if (lInitialPrecision == null) {
      lInitialPrecision = mCachedCPA.getInitialPrecision(pNode);
      mInitialPrecisionsCache.put(pNode, lInitialPrecision);
    }
    
    return lInitialPrecision;
  }

  @Override
  public <T extends ConfigurableProgramAnalysis> T retrieveWrappedCpa(
      Class<T> pType) {
    if (pType.isAssignableFrom(getClass())) {
      return pType.cast(this);
    }
    
    if (pType.isAssignableFrom(mCachedCPA.getClass())) {
      return pType.cast(mCachedCPA);
    }
    else if (mCachedCPA instanceof WrapperCPA) {
      return ((WrapperCPA)mCachedCPA).retrieveWrappedCpa(pType);
    }
    
    return null;
  }

}
