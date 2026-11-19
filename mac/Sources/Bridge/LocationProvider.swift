import CoreLocation
import Foundation

/// One-shot position of this Mac, for "Where is my Mac" on the phone.
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    enum Failure: Error { case denied, unavailable }

    private let manager = CLLocationManager()
    private var completion: ((Result<CLLocation, Error>) -> Void)?
    private var timeout: DispatchWorkItem?

    func request(_ done: @escaping (Result<CLLocation, Error>) -> Void) {
        completion = done
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        let t = DispatchWorkItem { [weak self] in self?.finish(.failure(Failure.unavailable)) }
        timeout = t
        DispatchQueue.main.asyncAfter(deadline: .now() + 25, execute: t)
        switch manager.authorizationStatus {
        case .denied, .restricted:
            finish(.failure(Failure.denied))
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            manager.requestLocation()
        }
    }

    private func finish(_ r: Result<CLLocation, Error>) {
        timeout?.cancel(); timeout = nil
        guard let c = completion else { return }
        completion = nil
        c(r)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .denied, .restricted: finish(.failure(Failure.denied))
        case .notDetermined: break
        default: manager.requestLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last { finish(.success(l)) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure((error as? CLError)?.code == .denied ? Failure.denied : Failure.unavailable))
    }
}
