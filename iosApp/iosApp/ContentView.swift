import AVFoundation
import Shared
import SwiftUI
import UIKit

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
}

struct ComposeOverlay: UIViewControllerRepresentable {
    let controller: OverlayController

    func makeUIViewController(context: Context) -> UIViewController {
        controller.viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @StateObject private var camera = CameraController()

    var body: some View {
        VStack {
            ZStack {
                CameraPreview(session: camera.session)
                ComposeOverlay(controller: camera.overlay)
            }
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            Text(camera.summary)
            Button(camera.recording ? "기록 종료" : "기록 시작") { camera.toggleRecording() }
            Button(camera.compareMode ? "비교 모드: 켜짐 (10/8/4/2fps)" : "비교 모드: 꺼짐") { camera.toggleCompare() }
                .disabled(camera.recording)
            if camera.recording {
                Text("기록 중 \(camera.elapsedSec)초")
            } else if camera.finished {
                Text("종료되었습니다")
            }
        }
        .onAppear { camera.start() }
    }
}
