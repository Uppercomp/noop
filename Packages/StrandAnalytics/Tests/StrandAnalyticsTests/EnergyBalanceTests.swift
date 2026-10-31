import XCTest
@testable import StrandAnalytics

final class EnergyBalanceTests: XCTestCase {
    func testDeficitSurplusAndAbsentLogs() {
        XCTAssertNil(EnergyBalance.net(intake: nil, totalBurn: 2300))
        XCTAssertNil(EnergyBalance.net(intake: 2000, totalBurn: nil))
        XCTAssertEqual(EnergyBalance.net(intake: 1800, totalBurn: 2300), -500)
        XCTAssertEqual(EnergyBalance.net(intake: 2700, totalBurn: 2300), 400)
        XCTAssertEqual(EnergyBalance.net(intake: 2300, totalBurn: 2300), 0)
        XCTAssertNil(EnergyBalance.net(intake: -1, totalBurn: 2300))
        XCTAssertNil(EnergyBalance.net(intake: 2000, totalBurn: .infinity))
    }
}
