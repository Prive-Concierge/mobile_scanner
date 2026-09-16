import CoreGraphics
import Foundation
import Vision

/// One recognised text line from a still image, in full-image pixel
/// coordinates with the origin at the top-left (the convention the Dart
/// `TextCapture.fromNative` consumer expects).
struct StillTextLine {
    /// The recognizer's preferred reading.
    let text: String
    /// Ranked alternative readings, `text` first.
    let candidates: [String]
    let confidence: Float
    let box: CGRect
}

/// Text recognition for still images (gallery, clipboard, share sheet).
///
/// ## Why a still image gets more than one pass
///
/// The live camera adapts: if a poster is too small in frame the user
/// leans in and the next frame is bigger. A shared photo has no next
/// frame. A shop wall photographed from the counter puts each poster in
/// a small fraction of the image, and the account number on it a few
/// dozen pixels tall — the regime where a single full-image pass drops
/// or misreads digits.
///
/// So after the full pass, large images get a **zoom pass**: the image
/// is split into an overlapping grid and each tile is recognised on its
/// own, which gives Vision the same text at 2× the relative size. Tile
/// results are merged conservatively:
///
///  * a tile line that touches the tile's crop edge is discarded — it is
///    a fragment of something longer, and a fragment of an 11-digit
///    phone number can look exactly like a 10-digit account;
///  * a tile line that overlaps a full-pass line is discarded — the full
///    pass already saw it;
///  * everything else is *new* text the full pass missed, and is kept.
///
/// The output is one flat list in reading order so downstream grouping
/// and classification see a single coherent frame.
@available(iOS 13.0, macOS 10.15, *)
final class StillTextRecognizer {
    /// Images whose longer side is below this gain nothing from
    /// tiling — the tiles would be too small to carry legible text.
    static let zoomPassMinDimension: CGFloat = 1000

    /// Grid size for the zoom pass. 2×2 doubles relative text height
    /// for four extra recognitions; enough for poster-on-a-wall photos
    /// without making shares feel slow.
    static let zoomGrid = 2

    /// Each tile extends this fraction of its size into its neighbours
    /// so a line straddling a grid boundary is whole in at least one tile.
    static let zoomOverlap: CGFloat = 0.15

    /// A tile line closer than this fraction of the tile size to a crop
    /// edge is treated as cut off and dropped.
    static let edgeMargin: CGFloat = 0.015

    /// Tile lines overlapping an existing line by more than this share
    /// of the smaller box are duplicates.
    static let duplicateOverlap: CGFloat = 0.5

    /// Explicit floor for recognisable text height, relative to the
    /// image being analysed. Vision's documented default is 1/32; a
    /// poster number in a wall photo is often below that.
    static let minimumTextHeight: Float = 0.005

    let languages: [String]
    let languageCorrection: Bool
    let candidateCount: Int

    init(languages: [String], languageCorrection: Bool, candidateCount: Int) {
        self.languages = languages
        self.languageCorrection = languageCorrection
        self.candidateCount = max(1, candidateCount)
    }

    /// Full pass, then (for large images) the zoom pass, merged and
    /// sorted in reading order.
    func recognize(_ image: CGImage) throws -> [StillTextLine] {
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        let fullRect = CGRect(x: 0, y: 0, width: width, height: height)

        var lines = try recognizeRegion(image, crop: fullRect, imageBounds: fullRect, isTile: false)

        if max(width, height) >= StillTextRecognizer.zoomPassMinDimension {
            for tile in StillTextRecognizer.tiles(for: fullRect) {
                guard let cropped = image.cropping(to: tile) else { continue }
                let found = try recognizeRegion(cropped, crop: tile, imageBounds: fullRect, isTile: true)
                for line in found where !StillTextRecognizer.isDuplicate(line, of: lines) {
                    lines.append(line)
                }
            }
        }

        return StillTextRecognizer.readingOrder(lines)
    }

    /// Recognise one region. `crop` is the region's rect in full-image
    /// pixels; results are translated back into that space.
    private func recognizeRegion(
        _ image: CGImage,
        crop: CGRect,
        imageBounds: CGRect,
        isTile: Bool
    ) throws -> [StillTextLine] {
        var observations: [VNRecognizedTextObservation] = []
        var failure: Error?
        let request = VNRecognizeTextRequest { req, err in
            if let err = err {
                failure = err
                return
            }
            observations = (req.results as? [VNRecognizedTextObservation]) ?? []
        }
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = languageCorrection
        request.minimumTextHeight = StillTextRecognizer.minimumTextHeight
        if !languages.isEmpty {
            request.recognitionLanguages = languages
        }

        let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
        try handler.perform([request])
        if let failure = failure { throw failure }

        var out: [StillTextLine] = []
        for observation in observations {
            let ranked = observation.topCandidates(candidateCount)
            guard let top = ranked.first else { continue }
            // Vision boxes are normalised to the analysed image with the
            // origin bottom-left. Map into full-image pixels, top-left.
            let bb = observation.boundingBox
            let box = CGRect(
                x: crop.minX + bb.minX * crop.width,
                y: crop.minY + (1 - bb.maxY) * crop.height,
                width: bb.width * crop.width,
                height: bb.height * crop.height)
            if isTile && StillTextRecognizer.touchesCropEdge(box, crop: crop, imageBounds: imageBounds) {
                continue
            }
            out.append(StillTextLine(
                text: top.string,
                candidates: ranked.map { $0.string },
                confidence: top.confidence,
                box: box))
        }
        return out
    }

    /// Overlapping `zoomGrid × zoomGrid` tiles covering `bounds`.
    static func tiles(for bounds: CGRect) -> [CGRect] {
        let n = CGFloat(zoomGrid)
        let tileW = bounds.width / n
        let tileH = bounds.height / n
        let padX = tileW * zoomOverlap
        let padY = tileH * zoomOverlap
        var out: [CGRect] = []
        for row in 0..<zoomGrid {
            for col in 0..<zoomGrid {
                let rect = CGRect(
                    x: bounds.minX + CGFloat(col) * tileW - padX,
                    y: bounds.minY + CGFloat(row) * tileH - padY,
                    width: tileW + 2 * padX,
                    height: tileH + 2 * padY
                ).intersection(bounds).integral
                if !rect.isEmpty { out.append(rect) }
            }
        }
        return out
    }

    /// Whether `box` sits against an edge of `crop` that is *not* also
    /// an edge of the whole image — i.e. the text was cut by the tiling
    /// rather than by the photo's own border.
    static func touchesCropEdge(_ box: CGRect, crop: CGRect, imageBounds: CGRect) -> Bool {
        let mx = crop.width * edgeMargin
        let my = crop.height * edgeMargin
        let tolerance: CGFloat = 0.5
        if box.minX - crop.minX < mx && crop.minX - imageBounds.minX > tolerance { return true }
        if crop.maxX - box.maxX < mx && imageBounds.maxX - crop.maxX > tolerance { return true }
        if box.minY - crop.minY < my && crop.minY - imageBounds.minY > tolerance { return true }
        if crop.maxY - box.maxY < my && imageBounds.maxY - crop.maxY > tolerance { return true }
        return false
    }

    /// Whether `line` covers ground an existing line already covers.
    static func isDuplicate(_ line: StillTextLine, of existing: [StillTextLine]) -> Bool {
        let area = line.box.width * line.box.height
        guard area > 0 else { return true }
        for other in existing {
            let inter = line.box.intersection(other.box)
            if inter.isNull || inter.isEmpty { continue }
            let interArea = inter.width * inter.height
            let smaller = min(area, other.box.width * other.box.height)
            if smaller > 0 && interArea / smaller > duplicateOverlap { return true }
        }
        return false
    }

    /// Top-to-bottom, then left-to-right; lines whose vertical centres
    /// fall within half a line height of each other count as one row.
    static func readingOrder(_ lines: [StillTextLine]) -> [StillTextLine] {
        return lines.sorted { a, b in
            let tolerance = max(a.box.height, b.box.height) / 2
            if abs(a.box.midY - b.box.midY) <= tolerance {
                return a.box.minX < b.box.minX
            }
            return a.box.midY < b.box.midY
        }
    }
}
