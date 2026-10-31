import Foundation

/// Logged intake minus total expenditure. Missing food logs never become zero intake.
public enum EnergyBalance {
    public static func net(intake: Double?, totalBurn: Double?) -> Double? {
        guard let intake, let totalBurn, intake.isFinite, totalBurn.isFinite,
              intake >= 0, totalBurn > 0 else { return nil }
        return intake - totalBurn
    }
}
