/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.mapreduce.hadoop;

import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.LimitedPrivate;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.mapreduce.combine.MRCombiner;
import org.apache.tez.mapreduce.hadoop.DeprecatedKeys.MultiStageKeys;
import org.apache.tez.mapreduce.partition.MRPartitioner;

import com.google.common.base.Preconditions;

public class MultiStageMRConfToTezTranslator {

  private static final Log LOG = LogFactory.getLog(MultiStageMRConfToTezTranslator.class);

  private enum DeprecationReason {
    DEPRECATED_DIRECT_TRANSLATION, DEPRECATED_MULTI_STAGE
  }

  // FIXME Add unit tests.
  // This will convert configs to tez.<vertexName>.<OriginalProperty> for
  // properties which it understands. Doing this for the initial and final task
  // as well to verify functionality.
  //

  /**
   * Converts a single completely configured MR* job to something that can be
   * understood by the Tez MR runtime.
   * 
   * @param srcConf
   *          the configuration for the entire MR* job. Configs for the first
   *          and last stage are expected to be set at root level. Configs for
   *          intermediate stages will be prefixed with the stage number.
   * @return A translated MR* config with keys translated over to Tez.
   */
  // TODO Set the cause properly.
  public static Configuration convertMRToLinearTez(Configuration srcConf) {
    Configuration newConf = new Configuration(srcConf);

    int numIntermediateStages = MultiStageMRConfigUtil
        .getNumIntermediateStages(srcConf);
    boolean hasFinalReduceStage = (srcConf.getInt(MRJobConfig.NUM_REDUCES, 0) > 0);

    // Assuming no 0 map jobs, and the first stage is always a map.
    int totalStages = numIntermediateStages + (hasFinalReduceStage ? 2 : 1);
    int numEdges = totalStages - 1;

    Configuration[] allConfs = extractStageConfs(newConf, numEdges);

    for (int i = 0; i < allConfs.length; i++) {
      setStageKeysFromBaseConf(allConfs[i], srcConf, Integer.toString(i));
      processDirectConversion(allConfs[i]);
    }
    for (int i = 0; i < allConfs.length - 1; i++) {
      translateMultiStageWithSuccessor(allConfs[i], allConfs[i + 1]);

    }
    // Unset unnecessary keys in the last stage. Will end up being called for
    // single stage as well which should be harmless.
    translateMultiStageWithSuccessor(allConfs[allConfs.length - 1], null);

    for (int i = 0; i < allConfs.length; i++) {
      String vertexName;
      if (i == 0) {
        vertexName = MultiStageMRConfigUtil.getInitialMapVertexName();
      } else if (i == allConfs.length - 1) {
        vertexName = MultiStageMRConfigUtil.getFinalReduceVertexName();
      } else {
        // Intermediate vertices start at 1
        vertexName = MultiStageMRConfigUtil.getIntermediateStageVertexName(i);
      }
      MultiStageMRConfigUtil.addConfigurationForVertex(newConf, vertexName,
          allConfs[i]);
    }

    return newConf;
  }

  

  /**
   * Translates MR keys to Tez for the provided vertex conf. The conversion is
   * done in place.
   * 
   * This method should be called for each stage config of the MR* job. The call
   * for the first vertex should set the predecessorConf as null.
   * 
   * Since there's no separation of input / output params at the moment, the
   * config generated by this can be set as the configuration for Tez
   * Input/Output and Processor.
   * 
   * @param conf
   *          Configuration for the vertex being configured.
   * @param predecessorConf
   *          Configuration for the previous vertex in the MR* chain
   */
  @LimitedPrivate("Hive, Pig")
  @Unstable
  public static void translateVertexConfToTez(Configuration conf,
      Configuration predecessorConf) {
    convertVertexConfToTez(conf, predecessorConf);
  }

  /**
   * Given a source and destination vertex, returns the config which should be
   * used for the Output on this edge. The configs must be configured with tez
   * keys - or run through translateVertexConfToTez.
   * 
   * @param srcVertex
   *          The tez configuration for the source vertex.
   * @param destVertex
   *          The tez configuration for the destination vertex.
   * @return
   */
  @LimitedPrivate("Hive, Pig")
  @Unstable
  public static Configuration getOutputConfOnEdge(Configuration srcVertex,
      Configuration destVertex) {
    Preconditions.checkNotNull(srcVertex, "srcVertex cannot be null for an edge");
    Preconditions.checkNotNull(destVertex, "destVertex cannot be null for an edge");
    return srcVertex;
  }

  /**
   * Given a source and destination vertex, returns the config which should be
   * used for the Input on this edge. The configs must be configured with tez
   * keys - or run through translateVertexConfToTez.
   * 
   * @param srcVertex
   *          The tez configuration for the source vertex.
   * @param destVertex
   *          The tez configuration for the destination vertex.
   * @return
   */
  @LimitedPrivate("Hive, Pig")
  @Unstable
  public static Configuration getInputConfOnEdge(Configuration srcVertex,
      Configuration destVertex) {
    Preconditions.checkNotNull(srcVertex, "srcVertex cannot be null for an edge");
    Preconditions.checkNotNull(destVertex, "destVertex cannot be null for an edge");
    return destVertex;
  }

  private static void convertVertexConfToTez(Configuration vertexConf,
      Configuration predecessorConf) {
    setStageKeysFromBaseConf(vertexConf, vertexConf, "unknown");
    processDirectConversion(vertexConf);
    translateMultiStageWithPredecessor(vertexConf, predecessorConf);
  }

  /**
   * Constructs a list containing individual configuration for each stage of the
   * linear MR job, including the first map and last reduce if applicable.
   * 
   * Generates basic configurations - i.e. without inheriting any keys from the
   * top level conf. // TODO Validate this comment.
   */
  private static Configuration[] extractStageConfs(Configuration conf,
      int totalEdges) {
    int numStages = totalEdges + 1;
    Configuration confs[] = new Configuration[numStages];
    // TODO Make moer efficient instead of multiple scans.
    Configuration nonIntermediateConf = MultiStageMRConfigUtil
        .getAndRemoveBasicNonIntermediateStageConf(conf);
    if (numStages == 1) {
      confs[0] = nonIntermediateConf;
    } else {
      confs[0] = nonIntermediateConf;
      confs[numStages - 1] = new Configuration(nonIntermediateConf);
    }
    if (numStages > 2) {
      for (int i = 1; i < numStages - 1; i++) {
        confs[i] = MultiStageMRConfigUtil
            .getAndRemoveBasicIntermediateStageConf(conf, i);
      }
    }
    return confs;
  }
  
  
  
  /**
   * Given a single base MRR config, returns a list of complete stage
   * configurations.
   * 
   * @param conf
   * @return
   */
  @Private
  public static Configuration[] getStageConfs(Configuration conf) {
    int numIntermediateStages = MultiStageMRConfigUtil
        .getNumIntermediateStages(conf);
    boolean hasFinalReduceStage = (conf.getInt(MRJobConfig.NUM_REDUCES, 0) > 0);
    // Assuming no 0 map jobs, and the first stage is always a map.
    int numStages = numIntermediateStages + (hasFinalReduceStage ? 2 : 1);

    // Setup Tez partitioner class
    conf.set(TezJobConfig.TEZ_RUNTIME_PARTITIONER_CLASS,
        MRPartitioner.class.getName());
    
    // Setup Tez Combiner class if required.
    // This would already have been set since the call is via JobClient
    boolean useNewApi = conf.getBoolean("mapred.mapper.new-api", false);
    if (useNewApi) {
      if (conf.get(MRJobConfig.COMBINE_CLASS_ATTR) != null) {
        conf.set(TezJobConfig.TEZ_RUNTIME_COMBINER_CLASS, MRCombiner.class.getName());
      }
    } else {
      if (conf.get("mapred.combiner.class") != null) {
        conf.set(TezJobConfig.TEZ_RUNTIME_COMBINER_CLASS, MRCombiner.class.getName());
      }
    }

    Configuration confs[] = new Configuration[numStages];
    Configuration nonItermediateConf = MultiStageMRConfigUtil.extractStageConf(
        conf, "");
    if (numStages == 1) {
      confs[0] = nonItermediateConf;
      confs[0].setBoolean(MRConfig.IS_MAP_PROCESSOR, true);
    } else {
      confs[0] = nonItermediateConf;
      confs[numStages - 1] = new Configuration(nonItermediateConf);
      confs[numStages -1].setBoolean(MRConfig.IS_MAP_PROCESSOR, false);
    }
    if (numStages > 2) {
      for (int i = 1; i < numStages - 1; i++) {
        confs[i] = MultiStageMRConfigUtil.extractStageConf(conf,
            MultiStageMRConfigUtil.getPropertyNameForIntermediateStage(i, ""));
        confs[i].setBoolean(MRConfig.IS_MAP_PROCESSOR, false);
      }
    }
    return confs;
  }

  private static void processDirectConversion(Configuration conf) {
    for (Entry<String, String> dep : DeprecatedKeys.getMRToTezRuntimeParamMap()
        .entrySet()) {
      if (conf.get(dep.getKey()) != null) {
        // TODO Deprecation reason does not seem to reflect in the config ?
        // The ordering is important in case of keys which are also deprecated.
        // Unset will unset the deprecated keys and all it's variants.
        String value = conf.get(dep.getKey());
        conf.unset(dep.getKey());
        conf.set(dep.getValue(), value,
            DeprecationReason.DEPRECATED_DIRECT_TRANSLATION.name());
      }
    }
  }

  /**
   * Takes as parameters configurations for the vertex and it's predecessor
   * (already translated to Tez). Modifies the vertex conf in place.
   */
  private static void translateMultiStageWithPredecessor(
      Configuration vertexConf, Configuration predecessorConf) {
    Preconditions.checkNotNull(vertexConf,
        "Configuration for vertex being translated cannot be null");
    for (Entry<String, Map<MultiStageKeys, String>> dep : DeprecatedKeys
        .getMultiStageParamMap().entrySet()) {
      if (vertexConf.get(dep.getKey()) != null) {
        String value = vertexConf.get(dep.getKey());
        vertexConf.unset(dep.getKey());
        vertexConf.set(dep.getValue().get(MultiStageKeys.OUTPUT), value,
            DeprecationReason.DEPRECATED_MULTI_STAGE.name());
      }
      // Set keys from the predecessor conf.
      if (predecessorConf != null) {
        String expPredecessorKey = dep.getValue().get(MultiStageKeys.OUTPUT);
        if (predecessorConf.get(expPredecessorKey) != null) {
          String value = predecessorConf.get(expPredecessorKey);
          vertexConf.set(dep.getValue().get(MultiStageKeys.INPUT), value);
        }
      }
    }
  }

  /**
   * Takes as parameters configurations for the vertex and it's successor.
   * Modifies both in place.
   */
  private static void translateMultiStageWithSuccessor(Configuration srcVertexConf,
      Configuration destVertexConf) {
    // All MR keys which need such translation are specified at src - hence,
    // this is ok.
    // No key exists in which the map is inferring something based on the reduce
    // value.
    for (Entry<String, Map<MultiStageKeys, String>> dep : DeprecatedKeys
        .getMultiStageParamMap().entrySet()) {
      if (srcVertexConf.get(dep.getKey()) != null) {
        if (destVertexConf != null) {
          String value = srcVertexConf.get(dep.getKey());
          srcVertexConf.unset(dep.getKey());
          srcVertexConf.set(dep.getValue().get(MultiStageKeys.OUTPUT), value,
              DeprecationReason.DEPRECATED_MULTI_STAGE.name());
          destVertexConf.set(dep.getValue().get(MultiStageKeys.INPUT), value,
              DeprecationReason.DEPRECATED_MULTI_STAGE.name());
        } else { // Last stage. Just remove the key reference.
          srcVertexConf.unset(dep.getKey());
        }
      }
    }
  }

  /**
   * Pulls in specific keys from the base configuration, if they are not set at
   * the stage level. An explicit list of keys is copied over (not all), which
   * require translation to tez keys.
   */
  private static void setStageKeysFromBaseConf(Configuration conf,
      Configuration baseConf, String stage) {
    JobConf jobConf = new JobConf(baseConf);
    // Don't clobber explicit tez config.
    if (conf.get(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_OUTPUT_KEY_CLASS) == null) {
      // If this is set, but the comparator is not set, and their types differ -
      // the job will break.
      if (conf.get(MRJobConfig.MAP_OUTPUT_KEY_CLASS) == null) {
        // Pull this in from the baseConf
        conf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS, jobConf
            .getMapOutputKeyClass().getName());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Setting " + MRJobConfig.MAP_OUTPUT_KEY_CLASS
              + " for stage: " + stage
              + " based on job level configuration. Value: "
              + conf.get(MRJobConfig.MAP_OUTPUT_KEY_CLASS));
        }
      }
    }

    if (conf.get(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_OUTPUT_VALUE_CLASS) == null) {
      if (conf.get(MRJobConfig.MAP_OUTPUT_VALUE_CLASS) == null) {
        conf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS, jobConf
            .getMapOutputValueClass().getName());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Setting " + MRJobConfig.MAP_OUTPUT_VALUE_CLASS
              + " for stage: " + stage
              + " based on job level configuration. Value: "
              + conf.get(MRJobConfig.MAP_OUTPUT_VALUE_CLASS));
        }
      }
    }
  }
}
