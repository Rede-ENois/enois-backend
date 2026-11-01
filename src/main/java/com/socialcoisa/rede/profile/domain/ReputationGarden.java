package com.socialcoisa.rede.profile.domain;

/**
 * The profile garden: how far each reputation component has grown, as a stage the UI draws as a
 * plant (0 seed to 3 full bloom), plus a health level that only dims its colors.
 *
 * <p>Stages come from the fraction of each component's cap: below 10% is 0, below 35% is 1, below
 * 70% is 2, otherwise 3. Health is 2 with no penalty, 1 up to 150 points, 0 above. Unlike the numeric
 * reputation, the garden is computed for every account, verified or not.</p>
 */
public record ReputationGarden(int trajectory, int relations, int contribution, int tenure, int health) {

	public static final int TRAJECTORY_CAP = 195;
	public static final int RELATIONS_CAP = 300;
	public static final int CONTRIBUTION_CAP = 420;
	public static final int TENURE_CAP = 120;
	private static final int MILD_PENALTY = 150;

	public static ReputationGarden of(double trajectory, double relations, double contribution, double tenure,
			int penalty) {
		return new ReputationGarden(stage(trajectory, TRAJECTORY_CAP), stage(relations, RELATIONS_CAP),
			stage(contribution, CONTRIBUTION_CAP), stage(tenure, TENURE_CAP), health(penalty));
	}

	static int stage(double value, int cap) {
		double fraction = value / cap;
		if (fraction < 0.10) {
			return 0;
		}
		if (fraction < 0.35) {
			return 1;
		}
		return fraction < 0.70 ? 2 : 3;
	}

	static int health(int penalty) {
		if (penalty <= 0) {
			return 2;
		}
		return penalty <= MILD_PENALTY ? 1 : 0;
	}
}
