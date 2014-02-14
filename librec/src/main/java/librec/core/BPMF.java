package librec.core;

import happy.coding.io.Strings;
import happy.coding.math.Randoms;
import librec.data.DenseMatrix;
import librec.data.DenseVector;
import librec.data.MatrixEntry;
import librec.data.SparseMatrix;
import librec.data.SparseVector;
import librec.intf.IterativeRecommender;

/**
 * Salakhutdinov and Mnih, <strong>Bayesian Probabilistic Matrix Factorization
 * using Markov Chain Monte Carlo</strong>, ICML 2008. <br/>
 * 
 * Matlab version is provided by the authors via <a
 * href="http://www.utstat.toronto.edu/~rsalakhu/BPMF.html">this link</a>.
 * 
 * @author guoguibing
 * 
 */
public class BPMF extends IterativeRecommender {

	public BPMF(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {
		super(trainMatrix, testMatrix, fold);

		algoName = "BayesianPMF";
	}

	@Override
	protected void buildModel() {

		// Initialize hierarchical priors
		int beta = 2; // observation noise (precision)
		DenseVector mu_u = new DenseVector(numFactors);
		DenseVector mu_m = new DenseVector(numFactors);

		// parameters of Inv-Whishart distribution
		DenseMatrix WI_u = DenseMatrix.eye(numFactors);
		int b0_u = 2;
		int df_u = numFactors;
		DenseVector mu0_u = new DenseVector(numFactors);

		DenseMatrix WI_m = DenseMatrix.eye(numFactors);
		int b0_m = 2;
		int df_m = numFactors;
		DenseVector mu0_m = new DenseVector(numFactors);

		// initializing Bayesian PMF using MAP solution found by PMF
		P = new DenseMatrix(numUsers, numFactors);
		Q = new DenseMatrix(numItems, numFactors);

		P.init(0, 1);
		Q.init(0, 1);

		for (int f = 0; f < numFactors; f++) {
			mu_u.set(f, P.column(f).mean());
			mu_m.set(f, Q.column(f).mean());
		}

		try {
			DenseMatrix alpha_u = P.cov().inv();
			DenseMatrix alpha_m = Q.cov().inv();

			// Iteration:
			DenseVector x_bar = new DenseVector(numFactors);
			DenseVector normalRdn = new DenseVector(numFactors);

			DenseMatrix S_bar, WI_post, lam;
			DenseVector mu_temp;
			double df_upost, df_mpost;

			for (int iter = 1; iter < maxIters; iter++) {
				// Sample from user hyper parameters:
				int M = numUsers;

				for (int f = 0; f < numFactors; f++)
					x_bar.set(f, P.column(f).mean());

				S_bar = P.cov();

				DenseVector mu0_u_x_bar = mu0_u.sub(x_bar);
				DenseMatrix e1e2 = mu0_u_x_bar.outer(mu0_u_x_bar).scale(M * b0_u / (b0_u + M + 0.0));
				WI_post = WI_u.inv().add(S_bar.scale(M)).add(e1e2);
				WI_post = WI_post.inv();
				WI_post = WI_post.add(WI_post.transpose()).scale(0.5);

				df_upost = df_u + M;
				DenseMatrix wishrnd_u = wishart(WI_post, df_upost);
				if (wishrnd_u != null)
					alpha_u = wishrnd_u;
				mu_temp = (mu0_u.scale(b0_u).add(x_bar.scale(M))).scale(1 / (b0_u + M + 0.0));
				lam = alpha_u.scale(b0_u + M).inv().cholesky();

				if (lam != null) {
					lam = lam.transpose();

					normalRdn = new DenseVector(numFactors);
					for (int f = 0; f < numFactors; f++)
						normalRdn.set(f, Randoms.gaussian(0, 1));

					mu_u = lam.mult(normalRdn).add(mu_temp);
				}

				// Sample from item hyper parameters:
				int N = numItems;

				for (int f = 0; f < numFactors; f++)
					x_bar.set(f, Q.column(f).mean());
				S_bar = Q.cov();

				DenseVector mu0_m_x_bar = mu0_m.sub(x_bar);
				DenseMatrix e3e4 = mu0_m_x_bar.outer(mu0_m_x_bar).scale(N * b0_m / (b0_m + N + 0.0));
				WI_post = WI_m.inv().add(S_bar.scale(N)).add(e3e4);
				WI_post = WI_post.inv();
				WI_post = WI_post.add(WI_post.transpose()).scale(0.5);

				df_mpost = df_m + N;
				DenseMatrix wishrnd_m = wishart(WI_post, df_mpost);
				if (wishrnd_m != null)
					alpha_m = wishrnd_m;
				mu_temp = (mu0_m.scale(b0_m).add(x_bar.scale(N))).scale(1 / (b0_m + N + 0.0));
				lam = alpha_m.scale(b0_m + N).inv().cholesky();

				if (lam != null) {
					lam = lam.transpose();

					normalRdn = new DenseVector(numFactors);
					for (int f = 0; f < numFactors; f++)
						normalRdn.set(f, Randoms.gaussian(0, 1));

					mu_m = lam.mult(normalRdn).add(mu_temp);
				}

				// Gibbs updates over user and item feature vectors given hyper
				// parameters:
				for (int gibbs = 1; gibbs < 2; gibbs++) {
					// Infer posterior distribution over all user feature
					// vectors
					for (int uu = 0; uu < numUsers; uu++) {
						// list of items rated by user uu:
						int[] ff = trainMatrix.row(uu).getIndex();

						if (ff == null || ff.length == 0)
							continue;

						int ff_idx = 0;
						for (int t = 0; t < ff.length; t++) {
							ff[ff_idx] = ff[t];
							ff_idx++;
						}

						// features of items rated by user uu:
						DenseMatrix MM = new DenseMatrix(ff_idx, numFactors);
						DenseVector rr = new DenseVector(ff_idx);
						int idx = 0;
						for (int t = 0; t < ff_idx; t++) {
							int i = ff[t];
							rr.set(idx, trainMatrix.get(uu, i) - globalMean);
							for (int f = 0; f < numFactors; f++)
								MM.set(idx, f, Q.get(i, f));

							idx++;
						}

						DenseMatrix covar = (alpha_u.add((MM.transpose().mult(MM)).scale(beta))).inv();
						DenseVector a = MM.transpose().mult(rr).scale(beta);
						DenseVector b = alpha_u.mult(mu_u);
						DenseVector mean_u = covar.mult(a.add(b));
						lam = covar.cholesky();

						if (lam != null) {
							lam = lam.transpose();
							for (int f = 0; f < numFactors; f++)
								normalRdn.set(f, Randoms.gaussian(0, 1));

							DenseVector w1_P1_uu = lam.mult(normalRdn).add(mean_u);

							for (int f = 0; f < numFactors; f++)
								P.set(uu, f, w1_P1_uu.get(f));
						}
					}

					// Infer posterior distribution over all movie feature
					// vectors
					for (int ii = 0; ii < numItems; ii++) {
						// list of users who rated item ii:
						int[] ff = trainMatrix.column(ii).getIndex();

						if (ff == null || ff.length == 0)
							continue;

						int ff_idx = 0;
						for (int t = 0; t < ff.length; t++) {
							ff[ff_idx] = ff[t];
							ff_idx++;
						}

						// features of users who rated item ii:
						DenseMatrix MM = new DenseMatrix(ff_idx, numFactors);
						DenseVector rr = new DenseVector(ff_idx);
						int idx = 0;
						for (int t = 0; t < ff_idx; t++) {
							int u = ff[t];
							rr.set(idx, trainMatrix.get(u, ii) - globalMean);
							for (int f = 0; f < numFactors; f++)
								MM.set(idx, f, P.get(u, f));

							idx++;
						}

						DenseMatrix covar = alpha_m.add((MM.transpose().mult(MM)).scale(beta)).inv();
						DenseVector a = MM.transpose().mult(rr).scale(beta);
						DenseVector b = alpha_m.mult(mu_m);
						DenseVector mean_m = covar.mult(a.add(b));
						lam = covar.cholesky();

						if (lam != null) {
							lam = lam.transpose();
							for (int f = 0; f < numFactors; f++)
								normalRdn.set(f, Randoms.gaussian(0, 1));

							DenseVector w1_M1_ii = lam.mult(normalRdn).add(mean_m);

							for (int f = 0; f < numFactors; f++)
								Q.set(ii, f, w1_M1_ii.get(f));
						}
					}
				}

				errs = 0;
				loss = 0;
				for (MatrixEntry me : trainMatrix) {
					int u = me.row();
					int j = me.column();
					double ruj = me.get();
					if (ruj > 0) {
						double pred = predict(u, j);
						double euj = ruj - pred;

						errs += euj * euj;
						loss += euj * euj;
					}

				}
				errs *= 0.5;
				loss *= 0.5;

				if (isConverged(iter))
					break;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	/**
	 * Randomly sample a matrix from Wishart Distribution with the given
	 * parameters.
	 * 
	 * @param scale
	 *            scale parameter for Wishart Distribution.
	 * @param df
	 *            degree of freedom for Wishart Distribution.
	 * @return the sample randomly drawn from the given distribution.
	 */
	protected DenseMatrix wishart(DenseMatrix scale, double df) {
		DenseMatrix A = scale.cholesky();
		if (A == null)
			return null;

		int p = scale.numRows();
		DenseMatrix z = new DenseMatrix(p, p);

		for (int i = 0; i < p; i++) {
			for (int j = 0; j < p; j++) {
				z.set(i, j, Randoms.gaussian(0, 1));
			}
		}

		SparseVector y = new SparseVector(p);
		for (int i = 0; i < p; i++)
			y.set(i, Randoms.gamma((df - (i + 1)) / 2, 2));

		DenseMatrix B = new DenseMatrix(p, p);
		B.set(0, 0, y.get(0));

		if (p > 1) {
			// rest of diagonal:
			for (int j = 1; j < p; j++) {
				SparseVector zz = new SparseVector(j);
				for (int k = 0; k < j; k++) {
					zz.set(k, z.get(k, j));
				}
				B.set(j, j, y.get(j) + zz.inner(zz));
			}

			// first row and column:
			for (int j = 1; j < p; j++) {
				B.set(0, j, z.get(0, j) * Math.sqrt(y.get(0)));
				B.set(j, 0, B.get(0, j)); // mirror
			}
		}

		if (p > 2) {
			for (int j = 2; j < p; j++) {
				for (int i = 1; i <= j - 1; i++) {
					SparseVector zki = new SparseVector(i);
					SparseVector zkj = new SparseVector(i);

					for (int k = 0; k <= i - 1; k++) {
						zki.set(k, z.get(k, i));
						zkj.set(k, z.get(k, j));
					}
					B.set(i, j, z.get(i, j) * Math.sqrt(y.get(i)) + zki.inner(zkj));
					B.set(j, i, B.get(i, j)); // mirror
				}
			}
		}

		return A.transpose().mult(B).mult(A);
	}

	@Override
	public String toString() {
		return Strings.toString(new Object[] { numFactors, maxIters }, ",");
	}
}