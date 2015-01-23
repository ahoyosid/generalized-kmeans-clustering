/*
 * Licensed to the Massive Data Science and Derrick R. Burns under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Massive Data Science and Derrick R. Burns licenses this file to You under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.massivedatascience.clusterer

import com.massivedatascience.clusterer.util.BLAS._
import org.apache.spark.mllib.linalg.Vector


/**
 * A point with an additional single Double value that is used in distance computation.
 *
 * @param embedding  inhomogeneous coordinates of point following embedding
 * @param weight weight of point
 * @param f f(point)
 */
class BregmanPoint(embedding: Vector, weight: Double, val f: Double)
  extends ImmutableInhomogeneousVector(embedding, weight)

/**
 * A cluster center with an additional Double and an additional vector containing the gradient
 * that are used in distance computation.
 *
 * @param h inhomogeneous coordinates of center in the embedded space
 * @param weight weight of vector
 * @param dotGradMinusF  center dot gradient(center) - f(center)
 * @param gradient gradient of center
 */
class BregmanCenter(h: Vector, weight: Double, val dotGradMinusF: Double, val gradient: Vector)
  extends ImmutableHomogeneousVector(h, weight)


class BregmanPointOps(val divergence: BregmanDivergence, val clusterFactory: ClusterFactory)
  extends PointOps[BregmanPoint, BregmanCenter]
  with ClusterFactory {

  def getCentroid = clusterFactory.getCentroid

  val weightThreshold = 1e-4
  val distanceThreshold = 1e-8

  def embed(v: Vector): Vector = v

  /**
   * Bregman distance function
   *
   * The distance function is called in the innermost loop of the K-Means clustering algorithm.
   * Therefore, we seek to make the operation as efficient as possible.
   *
   * @param p point
   * @param c center
   * @return
   */
  def distance(p: BregmanPoint, c: BregmanCenter): Double = {
    if (c.weight <= weightThreshold) {
      Infinity
    } else if (p.weight <= weightThreshold) {
      0.0
    } else {
      val d = p.f + c.dotGradMinusF - dot(c.gradient, p.inhomogeneous)
      if (d < 0.0) 0.0 else d
    }
  }

  def homogeneousToPoint(h: Vector, weight: Double): BregmanPoint = {
    val embedding = embed(asInhomogeneous(h, weight))
    new BregmanPoint(embedding, weight, divergence.F(embedding))
  }

  def inhomogeneousToPoint(inh: Vector, weight: Double): BregmanPoint = {
    val embedding = embed(inh)
    new BregmanPoint(embedding, weight, divergence.F(embedding))
  }

  def toCenter(v: WeightedVector): BregmanCenter = {
    val h = v.homogeneous
    val w = v.weight
    val df = divergence.gradF(h, w)
    new BregmanCenter(h, w, dot(h, df) / w - divergence.F(h, w), df)
  }

  def toPoint(v: WeightedVector): BregmanPoint = {
    val inh = v.inhomogeneous
    new BregmanPoint(inh, v.weight, divergence.F(inh))
  }

  def centerMoved(v: BregmanPoint, w: BregmanCenter): Boolean =
    distance(v, w) > distanceThreshold
}

/**
 * Implements Kullback-Leibler divergence on dense vectors in R+ ** n
 */
object DenseKullbackLeiblerPointOps
  extends BregmanPointOps(new KullbackLeiblerDivergence(GeneralLog), DenseClusterFactory)

/**
 * Implements Generalized I-divergence on dense vectors in R+ ** n
 */
object GeneralizedIPointOps
  extends BregmanPointOps(new GeneralizedIDivergence(GeneralLog), DenseClusterFactory)

/**
 * Implements Squared Euclidean distance on dense vectors in R ** n
 */
object DenseSquaredEuclideanPointOps
  extends BregmanPointOps(SquaredEuclideanDistanceDivergence, DenseClusterFactory)

/**
 * Implements Squared Euclidean distance on sparse vectors in R ** n
 */
object SparseSquaredEuclideanPointOps
  extends BregmanPointOps(SquaredEuclideanDistanceDivergence, SparseClusterFactory)

/**
 * Implements Squared Euclidean distance on sparse vectors in R ** n by
 * embedding the sparse vectors into a dense space using Random Indexing
 *
 */
class RIEuclideanPointOps(dim: Int, on: Int)
  extends BregmanPointOps(SquaredEuclideanDistanceDivergence, DenseClusterFactory) {

  val embedding = new RandomIndexEmbedding(dim, on)
  override def embed(v: Vector): Vector = embedding.embed(v.copy)
}

/**
 * Implements Squared Euclidean distance on sparse vectors in R ** n by
 * embedding the sparse vectors of various dimensions.
 *
 */
object LowDimensionalRandomIndexedSquaredEuclideanPointOps extends RIEuclideanPointOps(256, 3)

object MediumDimensionalRandomIndexedSquaredEuclideanPointOps extends RIEuclideanPointOps(512, 4)

object HighDimensionalRandomIndexedSquaredEuclideanPointOps extends RIEuclideanPointOps(1024, 7)


/**
 * Implements logistic loss divergence on dense vectors in (0.0,1.0) ** n
 */

object LogisticLossPointOps
  extends BregmanPointOps(LogisticLossDivergence, DenseClusterFactory)

/**
 * Implements Itakura-Saito divergence on dense vectors in R+ ** n
 */
object ItakuraSaitoPointOps
  extends BregmanPointOps(new ItakuraSaitoDivergence(GeneralLog), DenseClusterFactory)

/**
 * Implements Kullback-Leibler divergence for sparse points in R+ ** n
 *
 * We smooth the points by adding a constant to each dimension and then re-normalize the points
 * to get points on the simplex in R+ ** n.  This works fine with n is small and
 * known.  When n is large or unknown, one often uses sparse representations.  However, smoothing
 * turns a sparse vector into a dense one, and when n is large, this space is prohibitive.
 *
 * This implementation approximates smoothing by adding a penalty equal to the sum of the
 * values of the point along dimensions that are no represented in the cluster center.
 *
 * Also, with sparse data, the centroid can be of high dimension.  To address this, we limit the
 * density of the centroid by dropping low frequency entries in the SparseCentroidProvider
 */
object SparseKullbackLeiblerPointOps
  extends BregmanPointOps(new KullbackLeiblerDivergence(GeneralLog), SparseClusterFactory) {
    /**
   * Smooth the center using a variant Laplacian smoothing.
   *
   * The distance is roughly the equivalent of adding 1 to the center for
   * each dimension of C that is zero in C but that is non-zero in P
   *
   * @return
   */
  override def distance(p: BregmanPoint, c: BregmanCenter): Double = {
    if (c.weight <= weightThreshold) {
      Infinity
    } else if (p.weight <= weightThreshold) {
      0.0
    } else {
      val smoothed = sumMissing(c.homogeneous, p.inhomogeneous)
      val d = p.f + c.dotGradMinusF - dot(c.gradient, p.inhomogeneous) + smoothed
      if (d < 0.0) 0.0 else d
    }
  }
}

/**
 * Implements the Kullback-Leibler divergence for dense points are in N+ ** n,
 * i.e. the entries in each vector are positive integers.
 */
object DiscreteDenseKullbackLeiblerPointOps
  extends BregmanPointOps(new KullbackLeiblerDivergence(DiscreteLog), DenseClusterFactory)

/**
 * Implements Kullback-Leibler divergence with dense points in N ** n and whose
 * weights equal the sum of the frequencies.
 *
 * Because KL divergence is not defined on
 * zero values, we smooth the centers by adding the unit vector to each center.
 *
 */
object DiscreteDenseSmoothedKullbackLeiblerPointOps
  extends BregmanPointOps(new KullbackLeiblerDivergence(DiscreteLog), DenseClusterFactory) {

  override def toCenter(v: WeightedVector): BregmanCenter = {
    val h = add(v.homogeneous, 1.0)
    val w = v.weight + v.homogeneous.size
    val df = divergence.gradF(h, w)
    new BregmanCenter(v.homogeneous, w, dot(h, df) / w - divergence.F(h, w), df)
  }
}


/**
 * One can create a symmetric version of the Kullback Leibler Divergence that can be clustered
 * by embedding the input points (which are a simplex in R+ ** n) into a new Euclidean space R ** N.
 *
 * See http://www-users.cs.umn.edu/~banerjee/papers/13/bregman-metric.pdf
 *
 * This one is
 *
 * distance(x,y) = KL(x,y) + KL(y,x) + (1/2) ||x-y||^2 + (1/2) || gradF(x) - gradF(y)||^2
 *
 * The embedding is simply
 *
 * x => x + gradF(x) (Lemma 1 with alpha = beta = 1)
 *
 */
object GeneralizedSymmetrizedKLPointOps
  extends BregmanPointOps(new KullbackLeiblerDivergence(GeneralLog), DenseClusterFactory) {

  override def embed(v: Vector): Vector = {
    val embeddedV = v.copy
    axpy(1.0, divergence.gradF(embeddedV), embeddedV)
  }
}
