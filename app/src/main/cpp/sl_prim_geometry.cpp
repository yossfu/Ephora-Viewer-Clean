// Second Life primitive geometry.
//
// A port of Linden Lab's volume generation code, taken from the official
// viewer (indra/llmath/llvolume.cpp) so that the shapes we render are the same
// shapes the official viewer builds:
//
//   LLProfile::genNGon / LLProfile::generate   -> profile ring + face list
//   LLPath::genNGon / LLPath::generate         -> swept path
//   LLVolume::generate                         -> vertex positions
//   LLVolumeFace::createSide / createCap       -> triangles, winding, uv
//
// Only the profile/path sweep is ported here; sculpt maps and mesh assets take
// different paths in the viewer (sculpt texture / GetMesh) and are handled
// elsewhere.
//
// Face ordering (the order the viewer uses, and the order TextureEntry indexes
// into) comes from LLProfile::generate:
//   face 0            path-begin cap        (only when the path is open)
//   then              outer side faces      (one per profile edge for square and
//                                            triangular profiles, a single face
//                                            for circular ones)
//   then              hollow inner side     (one face)
//   then              path-end cap          (only when the path is open)
//   then              profile-begin wall    (only when the profile is cut)
//   then              profile-end wall      (only when the profile is cut)
//
// Wire quantisation matches LLPathParams / LLProfileParams in
// indra/llmath/llvolume.h: signed bytes are reinterpreted, not negated; cuts
// are 0.00002 per unit, scale/taper/twist 0.01, revolutions 0.015 + 1.
//
// Vertices are not shared between faces, exactly like the viewer's per-face
// vertex buffers, so caps stay flat shaded while swept sides stay smooth. The
// winding of every face matches the viewer's index buffer.

#include "sl_prim_geometry.h"

#include <algorithm>
#include <cmath>

namespace slcore {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kCutQuanta = 0.00002f;
constexpr float kScaleQuanta = 0.01f;
constexpr float kRevQuanta = 0.015f;
constexpr int kMinDetailFaces = 6;  // LLProfile: MIN_DETAIL_FACES

// LL_PCODE_PROFILE_*
constexpr int kProfileCircle = 0x00;
constexpr int kProfileSquare = 0x01;
constexpr int kProfileIsoTri = 0x02;
constexpr int kProfileEqualTri = 0x03;
constexpr int kProfileRightTri = 0x04;
constexpr int kProfileCircleHalf = 0x05;

// LL_PCODE_HOLE_*
constexpr int kHoleCircle = 0x10;
constexpr int kHoleSquare = 0x20;
constexpr int kHoleTriangle = 0x30;

// LL_PCODE_PATH_*
constexpr int kPathLine = 0x10;
constexpr int kPathCircle = 0x20;
constexpr int kPathCircle2 = 0x30;

// LL_FACE_* (the profile-end walls use the end uv behaviour)
constexpr int kFacePathBegin = 1 << 0;
constexpr int kFacePathEnd = 1 << 1;
constexpr int kFaceInnerSide = 1 << 2;
constexpr int kFaceProfileBegin = 1 << 3;
constexpr int kFaceProfileEnd = 1 << 4;

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

inline float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }

// Linden's U8 -> S8 reinterpretation: the wire byte is two's complement.
inline float asSigned(int wire) { return static_cast<float>(static_cast<int8_t>(wire & 0xff)); }

inline bool finite3(float x, float y, float z) {
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(z);
}

struct PrimF {
    int pathType = kPathLine;
    int profileType = kProfileSquare;
    int holeType = 0;
    float pathBegin = 0.f;
    float pathEnd = 1.f;
    float scaleX = 1.f;
    float scaleY = 1.f;
    float shearX = 0.f;
    float shearY = 0.f;
    float twist = 0.f;
    float twistBegin = 0.f;
    float radiusOffset = 0.f;
    float taperX = 0.f;
    float taperY = 0.f;
    float revolutions = 1.f;
    float skew = 0.f;
    float profileBegin = 0.f;
    float profileEnd = 1.f;
    float profileHollow = 0.f;
    float detail = 3.f;
};

PrimF convert(const PrimParams& raw) {
    PrimF p;
    p.pathType = raw.pathCurve & 0xf0;
    p.profileType = raw.profileCurve & 0x0f;
    p.holeType = raw.profileCurve & 0xf0;
    p.pathBegin = clamp01(static_cast<float>(raw.pathBegin) * kCutQuanta);
    p.pathEnd = 1.f - clamp01(static_cast<float>(raw.pathEnd) * kCutQuanta);
    p.scaleX = (200.f - static_cast<float>(raw.pathScaleX)) * kScaleQuanta;
    p.scaleY = (200.f - static_cast<float>(raw.pathScaleY)) * kScaleQuanta;
    p.shearX = asSigned(raw.pathShearX) * kScaleQuanta;
    p.shearY = asSigned(raw.pathShearY) * kScaleQuanta;
    p.twist = asSigned(raw.pathTwist) * kScaleQuanta;
    p.twistBegin = asSigned(raw.pathTwistBegin) * kScaleQuanta;
    p.radiusOffset = asSigned(raw.pathRadiusOffset) * kScaleQuanta;
    p.taperX = asSigned(raw.pathTaperX) * kScaleQuanta;
    p.taperY = asSigned(raw.pathTaperY) * kScaleQuanta;
    p.revolutions = static_cast<float>(raw.pathRevolutions) * kRevQuanta + 1.f;
    p.skew = asSigned(raw.pathSkew) * kScaleQuanta;
    p.profileBegin = clamp01(static_cast<float>(raw.profileBegin) * kCutQuanta);
    p.profileEnd = 1.f - clamp01(static_cast<float>(raw.profileEnd) * kCutQuanta);
    p.profileHollow = clamp01(static_cast<float>(raw.profileHollow) * kCutQuanta);
    const int detail = raw.detail < 0 ? 0 : (raw.detail > 3 ? 3 : raw.detail);
    p.detail = static_cast<float>(detail);
    return p;
}

struct Vec3 {
    float x, y, z;
};

inline Vec3 add(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline Vec3 sub(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline float dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 cross(Vec3 a, Vec3 b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline float length(Vec3 a) { return std::sqrt(dot(a, a)); }

inline Vec3 normalise(Vec3 a) {
    const float len = length(a);
    if (len < 1e-12f) {
        return {0.f, 0.f, 1.f};
    }
    return {a.x / len, a.y / len, a.z / len};
}

struct Mat3 {
    float m[9];

    Vec3 rotate(Vec3 v) const {
        return {m[0] * v.x + m[1] * v.y + m[2] * v.z,
                m[3] * v.x + m[4] * v.y + m[5] * v.z,
                m[6] * v.x + m[7] * v.y + m[8] * v.z};
    }
};

Mat3 rotationZ(float angle) {
    const float c = std::cos(angle);
    const float s = std::sin(angle);
    return {{c, -s, 0.f, s, c, 0.f, 0.f, 0.f, 1.f}};
}

Mat3 rotationX(float angle) {
    const float c = std::cos(angle);
    const float s = std::sin(angle);
    return {{1.f, 0.f, 0.f, 0.f, c, -s, 0.f, s, c}};
}

Mat3 multiply(const Mat3& a, const Mat3& b) {
    Mat3 out{};
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            float sum = 0.f;
            for (int k = 0; k < 3; ++k) {
                sum += a.m[row * 3 + k] * b.m[k * 3 + col];
            }
            out.m[row * 3 + col] = sum;
        }
    }
    return out;
}

// A point on the profile. `s` is the profile texture coordinate: the viewer
// keeps it in the third component of the profile vector and scales it by 4 for
// squares and 3 for triangles.
struct ProfilePoint {
    float x;
    float y;
    float s;
};

struct PathPoint {
    Vec3 pos;
    Mat3 rot;
    float scaleX;
    float scaleY;
    float texT;
};

struct Path {
    std::vector<PathPoint> points;
    int pathType = kPathLine;
    bool open = true;
    bool valid = false;
};

// One face of LLProfile's face list: a run of profile points (the S axis) swept
// over every path point (the T axis).
struct Face {
    int faceId;
    bool cap;
    bool flat;
    bool endMask;
    std::vector<int> columns;
};

struct Profile {
    std::vector<ProfilePoint> points;
    std::vector<Face> sideFaces;
    std::vector<Face> walls;
    Face innerFace;
    int profileType = kProfileSquare;
    bool hollow = false;
    bool innerFlat = false;
    bool open = false;
    bool flatProfile = false;
    int total = 0;
    int totalOut = 0;
    int split = 0;
};

// LLProfile::genNGon(): appends an n-gon arc running from profileBegin to
// profileEnd. `split` adds extra points on every segment. If the arc is cut and
// the profile is not hollow, a centre point at the origin is appended: the
// viewer uses it as the cap fan apex and as the cut wall corner.
void genProfileNGon(const PrimF& p, int sides, float offset, float angScale, float sScale,
                    int split, std::vector<ProfilePoint>& out) {
    static const float kTableScale[8] = {1.f, 1.f, 1.f, 0.5f, 0.707107f, 0.53f, 0.525f, 0.5f};
    if (sides < 1) {
        return;
    }
    const float begin = p.profileBegin;
    const float end = p.profileEnd;
    const float tStep = 1.f / static_cast<float>(sides);
    const float angStep = 2.f * kPi * tStep * angScale;

    // Scale to have size "match" scale, so the object generally fills the
    // bounding box.
    const int totalSides = static_cast<int>(std::lround(static_cast<float>(sides) / angScale));
    float scale = 0.5f;
    if (totalSides >= 0 && totalSides < 8) {
        scale = kTableScale[totalSides];
    }

    float t = std::floor(begin * static_cast<float>(sides)) / static_cast<float>(sides);
    const float tFirst = t;
    float ang = 2.f * kPi * (t * angScale + offset);
    float pt1x = std::cos(ang) * scale;
    float pt1y = std::sin(ang) * scale;
    float pt1t = t;

    t += tStep;
    ang += angStep;
    float pt2x = std::cos(ang) * scale;
    float pt2y = std::sin(ang) * scale;
    float pt2t = t;

    // Only used if it is not almost exactly on an edge.
    float fraction = (begin - tFirst) * static_cast<float>(sides);
    if (fraction < 0.9999f) {
        out.push_back({lerp(pt1x, pt2x, fraction), lerp(pt1y, pt2y, fraction),
                       lerp(pt1t, pt2t, fraction) * sScale});
    }

    while (t < end) {
        pt1x = std::cos(ang) * scale;
        pt1y = std::sin(ang) * scale;
        pt1t = t;
        if (!out.empty() && split > 0) {
            const ProfilePoint base = out.back();
            for (int i = 0; i < split; ++i) {
                const float f = static_cast<float>(i + 1) / static_cast<float>(split + 1);
                out.push_back({lerp(base.x, pt1x, f), lerp(base.y, pt1y, f),
                               lerp(base.s, pt1t * sScale, f)});
            }
        }
        out.push_back({pt1x, pt1y, pt1t * sScale});
        t += tStep;
        ang += angStep;
    }

    // One final pass for the end cut.
    pt2x = std::cos(ang) * scale;
    pt2y = std::sin(ang) * scale;
    pt2t = t;
    fraction = (end - (t - tStep)) * static_cast<float>(sides);
    if (fraction > 0.0001f) {
        const float nx = lerp(pt1x, pt2x, fraction);
        const float ny = lerp(pt1y, pt2y, fraction);
        const float nt = lerp(pt1t, pt2t, fraction);
        if (!out.empty() && split > 0) {
            const ProfilePoint base = out.back();
            for (int i = 0; i < split; ++i) {
                const float f = static_cast<float>(i + 1) / static_cast<float>(split + 1);
                out.push_back({lerp(base.x, nx, f), lerp(base.y, ny, f),
                               lerp(base.s, nt * sScale, f)});
            }
        }
        out.push_back({nx, ny, nt * sScale});
    }

    if ((end - begin) * angScale < 0.99f) {
        if (p.profileHollow <= 0.f) {
            // Put a centre point in if not hollow.
            out.push_back({0.f, 0.f, 0.f});
        }
    }
}

// LLProfile::addHole(): appends a scaled copy of the profile as the inner ring
// and reverses it. The reversal is what turns the inner side face's winding
// inwards.
void addHole(const PrimF& p, int sides, float offset, float angScale, float sScale, int split,
             float boxHollow, bool flat, Profile& profile) {
    if (sides < 1) {
        return;
    }
    profile.totalOut = static_cast<int>(profile.points.size());
    genProfileNGon(p, sides, offset, angScale, sScale, split, profile.points);
    for (size_t i = static_cast<size_t>(profile.totalOut); i < profile.points.size(); ++i) {
        profile.points[i].x *= boxHollow;
        profile.points[i].y *= boxHollow;
        profile.points[i].s *= boxHollow;
    }
    std::reverse(profile.points.begin() + profile.totalOut, profile.points.end());
    profile.hollow = true;
    profile.innerFlat = flat;
    profile.innerFace.faceId = kFaceInnerSide;
    profile.innerFace.cap = false;
    profile.innerFace.flat = flat;
    profile.innerFace.endMask = false;
    profile.innerFace.columns.clear();
    for (int i = profile.totalOut; i < static_cast<int>(profile.points.size()); ++i) {
        profile.innerFace.columns.push_back(i);
    }
}

// LLProfile::generate(): builds the profile ring and its face list.
Profile buildProfile(const PrimF& p) {
    Profile profile;
    profile.split = static_cast<int>(p.detail * 0.66f);
    const int split = profile.split;
    const int profileType = p.profileType;
    profile.profileType = profileType;
    const int holeType = p.holeType;
    const float hollow = p.profileHollow;

    float circleDetail = static_cast<float>(kMinDetailFaces) * p.detail;
    if (hollow > 0.f && profileType == kProfileCircle && holeType == kHoleSquare) {
        // Snap to the next multiple of four sides so that corners line up.
        circleDetail = std::ceil(circleDetail / 4.f) * 4.f;
    }
    float halfDetail = static_cast<float>(kMinDetailFaces) * p.detail * 0.5f;
    if (hollow > 0.f && profileType == kProfileCircleHalf && holeType == kHoleSquare) {
        halfDetail = std::ceil(halfDetail / 2.f) * 2.f;
    }

    float angScale = 1.f;
    switch (profileType) {
        case kProfileSquare:
            profile.flatProfile = true;
            profile.innerFlat = true;
            genProfileNGon(p, 4, -0.375f, 1.f, 4.f, split, profile.points);
            break;
        case kProfileIsoTri:
        case kProfileEqualTri:
        case kProfileRightTri:
            profile.flatProfile = true;
            profile.innerFlat = true;
            genProfileNGon(p, 3, 0.f, 1.f, 3.f, split, profile.points);
            break;
        case kProfileCircleHalf:
            angScale = 0.5f;
            genProfileNGon(p, static_cast<int>(std::floor(halfDetail)), 0.5f, 0.5f, 1.f, 0,
                           profile.points);
            break;
        default:
            genProfileNGon(p, static_cast<int>(circleDetail), 0.f, 1.f, 1.f, 0, profile.points);
            break;
    }

    if (profile.points.empty()) {
        return profile;
    }

    // The openness flag the viewer has while the side faces are added. The half
    // circle's sphere special case below may override it afterwards.
    const bool genOpen = ((p.profileEnd - p.profileBegin) * angScale) < 0.99f;
    profile.open = genOpen;
    profile.total = static_cast<int>(profile.points.size());
    profile.totalOut = profile.total;
    const int outer = profile.total;

    int faceOrdinal = 0;
    if (profile.flatProfile) {
        // One side face per profile edge (LL_FACE_OUTER_SIDE_0 << i) for square
        // and triangular profiles.
        const float edges = (profileType == kProfileSquare) ? 4.f : 3.f;
        const int firstEdge = static_cast<int>(std::floor(p.profileBegin * edges));
        const int lastEdge = static_cast<int>(std::floor(p.profileEnd * edges + 0.999f));
        for (int edge = firstEdge; edge < lastEdge; ++edge) {
            const int begin = faceOrdinal * (split + 1);
            int count = split + 2;
            if (begin + count > outer) {
                count = outer - begin;
            }
            if (count < 2) {
                continue;
            }
            Face face;
            face.faceId = 0;
            face.cap = false;
            face.flat = true;
            face.endMask = false;
            for (int i = 0; i < count; ++i) {
                face.columns.push_back(begin + i);
            }
            profile.sideFaces.push_back(face);
            ++faceOrdinal;
        }
    } else {
        // Circular profiles are swept as a single side face. When the profile is
        // cut the viewer drops the appended centre point; when it is hollow
        // there is no centre point to drop.
        const int count = (genOpen && hollow <= 0.f) ? outer - 1 : outer;
        if (count >= 2) {
            Face face;
            face.faceId = 0;
            face.cap = false;
            face.flat = false;
            face.endMask = false;
            for (int i = 0; i < count; ++i) {
                face.columns.push_back(i);
            }
            profile.sideFaces.push_back(face);
            ++faceOrdinal;
        }
    }

    if (hollow > 0.f) {
        const int circleHoleSides = kMinDetailFaces * static_cast<int>(p.detail);
        switch (profileType) {
            case kProfileSquare:
                if (holeType == kHoleTriangle) {
                    addHole(p, 3, -0.375f, 1.f, 4.f, split, hollow, true, profile);
                } else if (holeType == kHoleCircle) {
                    addHole(p, circleHoleSides, -0.375f, 1.f, 4.f, 0, hollow, false, profile);
                } else {
                    addHole(p, 4, -0.375f, 1.f, 4.f, split, hollow, true, profile);
                }
                break;
            case kProfileIsoTri:
            case kProfileEqualTri:
            case kProfileRightTri: {
                // Swept triangles need smaller hollowness values, because the
                // triangle does not fill the bounding box.
                const float triangleHollow = hollow / 2.f;
                if (holeType == kHoleCircle) {
                    addHole(p, circleHoleSides, 0.f, 1.f, 3.f, 0, triangleHollow, false, profile);
                } else if (holeType == kHoleSquare) {
                    addHole(p, 4, 0.f, 1.f, 3.f, split, triangleHollow, true, profile);
                } else {
                    addHole(p, 3, 0.f, 1.f, 3.f, split, triangleHollow, true, profile);
                }
                break;
            }
            case kProfileCircleHalf:
                if (holeType == kHoleSquare) {
                    addHole(p, 2, 0.5f, 0.5f, 1.f, split, hollow, true, profile);
                } else if (holeType == kHoleTriangle) {
                    addHole(p, 3, 0.5f, 0.5f, 1.f, split, hollow, true, profile);
                } else {
                    addHole(p, static_cast<int>(circleDetail), 0.5f, 0.5f, 1.f, 0, hollow, false,
                            profile);
                }
                break;
            default:
                if (holeType == kHoleSquare) {
                    addHole(p, 4, 0.f, 1.f, 1.f, split, hollow, true, profile);
                } else if (holeType == kHoleTriangle) {
                    addHole(p, 3, 0.f, 1.f, 1.f, split, hollow, true, profile);
                } else {
                    addHole(p, static_cast<int>(circleDetail), 0.f, 1.f, 1.f, 0, hollow, false,
                            profile);
                }
                break;
        }
    }

    if (profileType == kProfileCircleHalf) {
        // Special case for openness of sphere.
        if ((p.profileEnd - p.profileBegin) < 1.f) {
            profile.open = true;
        } else if (hollow <= 0.f) {
            profile.open = false;
            profile.points.push_back(profile.points[0]);
            profile.total = static_cast<int>(profile.points.size());
        }
    }

    // Interior edge caps: the two cut walls.
    if (profile.open) {
        Face begin;
        begin.faceId = kFaceProfileBegin;
        begin.cap = false;
        begin.flat = true;
        begin.endMask = true;
        begin.columns.push_back(profile.total - 1);
        begin.columns.push_back(0);

        Face end;
        end.faceId = kFaceProfileEnd;
        end.cap = false;
        end.flat = true;
        end.endMask = true;
        if (profile.hollow) {
            end.columns.push_back(profile.totalOut - 1);
            end.columns.push_back(profile.totalOut);
        } else {
            end.columns.push_back(profile.total - 2);
            end.columns.push_back(profile.total - 1);
        }
        profile.walls.push_back(begin);
        profile.walls.push_back(end);
    }

    return profile;
}

// LLPath::genNGon(): a circular path in the YZ plane.
void genNGonPath(const PrimF& p, int sides, Path& path) {
    static const float kTableScale[8] = {1.f, 1.f, 1.f, 0.5f, 0.707107f, 0.53f, 0.525f, 0.5f};
    const float skew = p.skew;
    const float skewMag = std::fabs(skew);
    const float holeX = p.scaleX * (1.f - skewMag);
    const float holeY = p.scaleY;

    float taperXBegin = 1.f;
    float taperXEnd = 1.f - p.taperX;
    float taperYBegin = 1.f;
    float taperYEnd = 1.f - p.taperY;
    if (taperXEnd > 1.f) {
        taperXBegin = 2.f - taperXEnd;
        taperXEnd = 1.f;
    }
    if (taperYEnd > 1.f) {
        taperYBegin = 2.f - taperYEnd;
        taperYEnd = 1.f;
    }

    // For spheres, the radius is usually zero.
    float radiusStart = 0.5f;
    if (sides < 8) {
        radiusStart = kTableScale[sides];
    }
    radiusStart *= 1.f - holeY;
    float radiusEnd = radiusStart;
    if (p.radiusOffset < 0.f) {
        radiusStart *= 1.f + p.radiusOffset;
    } else {
        radiusEnd *= 1.f - p.radiusOffset;
    }

    path.open = ((p.pathEnd - p.pathBegin < 1.f) || (skewMag > 0.001f) ||
                 (std::fabs(taperXEnd - taperXBegin) > 0.001f) ||
                 (std::fabs(taperYEnd - taperYBegin) > 0.001f) ||
                 (std::fabs(radiusEnd - radiusStart) > 0.001f));

    const float step = 1.f / static_cast<float>(sides);
    const auto emit = [&](float t) {
        const float ang = 2.f * kPi * p.revolutions * t;
        const float radius = lerp(radiusStart, radiusEnd, t);
        const float s = std::sin(ang) * radius;
        const float c = std::cos(ang) * radius;
        PathPoint point;
        point.pos = {lerp(0.f, p.shearX, s) + lerp(-skew, skew, t) * 0.5f,
                     c + lerp(0.f, p.shearY, s), s};
        point.scaleX = holeX * lerp(taperXBegin, taperXEnd, t);
        point.scaleY = holeY * lerp(taperYBegin, taperYEnd, t);
        point.texT = t;
        const float twistAngle = lerp(p.twistBegin, p.twist, t) * 2.f * kPi - kPi;
        // Linden's quaternions use row-vector matrices and their quaternion
        // product is the reversed Hamilton product, so LLMatrix3(twist * qang)
        // is Rx(ang) * Rz(twist) in column-vector terms.
        point.rot = multiply(rotationX(ang), rotationZ(twistAngle));
        path.points.push_back(point);
    };

    float t = p.pathBegin;
    emit(t);
    t += step;
    // Snap to a quantized parameter, so that the cut does not affect most
    // sample points.
    t = static_cast<float>(static_cast<int>(t * static_cast<float>(sides))) /
        static_cast<float>(sides);
    while (t < p.pathEnd) {
        emit(t);
        t += step;
    }
    emit(p.pathEnd);
}

// LLPath::generate()
Path buildPath(const PrimF& p) {
    Path path;
    path.pathType = p.pathType;
    const int split = static_cast<int>(p.detail * 0.66f);
    switch (p.pathType) {
        case kPathCircle:
        case kPathCircle2: {
            int sides;
            if (p.pathType == kPathCircle2) {
                sides = static_cast<int>(std::floor(static_cast<float>(kMinDetailFaces) * p.detail));
            } else {
                // Increase the detail as the revolutions and twist increase.
                const float twistMag = std::fabs(p.twistBegin - p.twist);
                sides = static_cast<int>(std::floor(
                        std::floor(static_cast<float>(kMinDetailFaces) * p.detail +
                                   twistMag * 3.5f * (p.detail - 0.5f)) *
                        p.revolutions));
            }
            if (sides < 1) {
                return path;
            }
            genNGonPath(p, sides, path);
            if (p.pathType == kPathCircle2) {
                float toggle = 0.5f;
                for (PathPoint& point : path.points) {
                    point.pos.x = toggle;
                    toggle = (toggle == 0.5f) ? -0.5f : 0.5f;
                }
            }
            break;
        }
        case kPathLine:
        default: {
            const float twists = std::fabs(p.twistBegin - p.twist);
            int count = static_cast<int>(std::floor(twists * 3.5f * (p.detail - 0.5f))) + 2;
            if (count < split + 2) {
                count = split + 2;
            }
            if (count < 2) {
                count = 2;
            }
            const float step = 1.f / static_cast<float>(count - 1);
            const float beginScaleX = p.scaleX > 1.f ? 2.f - p.scaleX : 1.f;
            const float beginScaleY = p.scaleY > 1.f ? 2.f - p.scaleY : 1.f;
            const float endScaleX = p.scaleX < 1.f ? p.scaleX : 1.f;
            const float endScaleY = p.scaleY < 1.f ? p.scaleY : 1.f;
            path.points.reserve(static_cast<size_t>(count));
            for (int i = 0; i < count; ++i) {
                const float t = lerp(p.pathBegin, p.pathEnd, static_cast<float>(i) * step);
                PathPoint point;
                point.pos = {lerp(0.f, p.shearX, t), lerp(0.f, p.shearY, t), t - 0.5f};
                point.rot = rotationZ(kPi * lerp(p.twistBegin, p.twist, t));
                point.scaleX = lerp(beginScaleX, endScaleX, t);
                point.scaleY = lerp(beginScaleY, endScaleY, t);
                point.texT = t;
                path.points.push_back(point);
            }
            path.open = true;
            break;
        }
    }
    if (p.twist != p.twistBegin) {
        path.open = true;
    }
    path.valid = path.points.size() >= 2;
    for (const PathPoint& point : path.points) {
        if (!finite3(point.pos.x, point.pos.y, point.pos.z)) {
            path.valid = false;
        }
    }
    return path;
}

// LLVolume::generate(): the profile is scaled first (which drops the profile's
// third component, the texture coordinate), then rotated, then translated.
inline Vec3 sweep(const PathPoint& path, const ProfilePoint& profile) {
    const Vec3 scaled{path.scaleX * profile.x, path.scaleY * profile.y, 0.f};
    return add(path.rot.rotate(scaled), path.pos);
}

struct Builder {
    MeshData mesh;
    std::vector<FaceGroup> faces;

    int addVertex(Vec3 position, float u, float v) {
        mesh.vertices.push_back(position.x);
        mesh.vertices.push_back(position.y);
        mesh.vertices.push_back(position.z);
        mesh.vertices.push_back(0.f);
        mesh.vertices.push_back(0.f);
        mesh.vertices.push_back(0.f);
        mesh.vertices.push_back(u);
        mesh.vertices.push_back(v);
        return static_cast<int>(mesh.vertices.size() / 8) - 1;
    }

    void accumulate(int a, int b, int c) {
        const Vec3 pa{mesh.vertices[static_cast<size_t>(a) * 8 + 0],
                      mesh.vertices[static_cast<size_t>(a) * 8 + 1],
                      mesh.vertices[static_cast<size_t>(a) * 8 + 2]};
        const Vec3 pb{mesh.vertices[static_cast<size_t>(b) * 8 + 0],
                      mesh.vertices[static_cast<size_t>(b) * 8 + 1],
                      mesh.vertices[static_cast<size_t>(b) * 8 + 2]};
        const Vec3 pc{mesh.vertices[static_cast<size_t>(c) * 8 + 0],
                      mesh.vertices[static_cast<size_t>(c) * 8 + 1],
                      mesh.vertices[static_cast<size_t>(c) * 8 + 2]};
        const Vec3 n = cross(sub(pb, pa), sub(pc, pa));
        for (int index : {a, b, c}) {
            mesh.vertices[static_cast<size_t>(index) * 8 + 3] += n.x;
            mesh.vertices[static_cast<size_t>(index) * 8 + 4] += n.y;
            mesh.vertices[static_cast<size_t>(index) * 8 + 5] += n.z;
        }
    }

    void addTriangle(int a, int b, int c) {
        mesh.indices.push_back(static_cast<uint32_t>(a));
        mesh.indices.push_back(static_cast<uint32_t>(b));
        mesh.indices.push_back(static_cast<uint32_t>(c));
        accumulate(a, b, c);
    }

    void addQuad(int a, int b, int c, int d) {
        addTriangle(a, b, c);
        addTriangle(a, c, d);
    }

    void beginFace(int faceIndex) {
        FaceGroup group;
        group.faceIndex = faceIndex;
        group.firstIndex = static_cast<uint32_t>(mesh.indices.size());
        group.indexCount = 0;
        faces.push_back(group);
    }

    void endFace() {
        if (faces.empty()) {
            return;
        }
        FaceGroup& group = faces.back();
        group.indexCount = static_cast<uint32_t>(mesh.indices.size()) - group.firstIndex;
        if (group.indexCount == 0) {
            faces.pop_back();
        }
    }
};

// LLVolumeFace::createSide(): a grid of (path point) x (profile point) quads.
// The winding matches the viewer's index buffer exactly.
void emitSide(Builder& builder, const Profile& profile, const Path& path, const Face& face,
              bool perTriangleVertex) {
    const int columns = static_cast<int>(face.columns.size());
    const int rows = static_cast<int>(path.points.size());
    if (columns < 2 || rows < 2) {
        return;
    }
    const float beginStex = std::floor(profile.points[static_cast<size_t>(face.columns[0])].s);
    const auto columnU = [&](int column) -> float {
        if (face.endMask) {
            return column ? 1.f : 0.f;
        }
        const float s = profile.points[static_cast<size_t>(face.columns[column])].s;
        return face.flat ? s - beginStex : s;
    };
    std::vector<int> grid(static_cast<size_t>(rows) * static_cast<size_t>(columns), -1);
    const auto vertexAt = [&](int row, int column) -> int {
        if (perTriangleVertex) {
            const ProfilePoint& point = profile.points[static_cast<size_t>(face.columns[column])];
            return builder.addVertex(sweep(path.points[static_cast<size_t>(row)], point),
                                     columnU(column), path.points[static_cast<size_t>(row)].texT);
        }
        int& slot = grid[static_cast<size_t>(row) * static_cast<size_t>(columns) +
                         static_cast<size_t>(column)];
        if (slot < 0) {
            const ProfilePoint& point = profile.points[static_cast<size_t>(face.columns[column])];
            slot = builder.addVertex(sweep(path.points[static_cast<size_t>(row)], point),
                                     columnU(column), path.points[static_cast<size_t>(row)].texT);
        }
        return slot;
    };
    for (int row = 0; row + 1 < rows; ++row) {
        for (int column = 0; column + 1 < columns; ++column) {
            const int a = vertexAt(row, column);
            const int b = vertexAt(row, column + 1);
            const int c = vertexAt(row + 1, column + 1);
            const int d = vertexAt(row + 1, column);
            builder.addQuad(a, b, c, d);
        }
    }

    if (perTriangleVertex) {
        return;
    }

    // LLVolumeFace::createSide(): normals are evened out at the seams of the
    // surface, and the poles of a sphere get an explicit axis-aligned normal
    // because the accumulated ones are degenerate there.
    const auto vertexNormal = [&](int row, int column) -> Vec3 {
        const int index = grid[static_cast<size_t>(row) * static_cast<size_t>(columns) +
                              static_cast<size_t>(column)];
        if (index < 0) {
            return {0.f, 0.f, 0.f};
        }
        const size_t base = static_cast<size_t>(index) * 8 + 3;
        return {builder.mesh.vertices[base], builder.mesh.vertices[base + 1],
                builder.mesh.vertices[base + 2]};
    };
    const auto setVertexNormal = [&](int row, int column, Vec3 normal) {
        const int index = grid[static_cast<size_t>(row) * static_cast<size_t>(columns) +
                              static_cast<size_t>(column)];
        if (index < 0) {
            return;
        }
        const size_t base = static_cast<size_t>(index) * 8 + 3;
        builder.mesh.vertices[base] = normal.x;
        builder.mesh.vertices[base + 1] = normal.y;
        builder.mesh.vertices[base + 2] = normal.z;
    };
    const auto vertexPosition = [&](int row, int column) -> Vec3 {
        const int index = grid[static_cast<size_t>(row) * static_cast<size_t>(columns) +
                              static_cast<size_t>(column)];
        if (index < 0) {
            return {0.f, 0.f, 0.f};
        }
        const size_t base = static_cast<size_t>(index) * 8;
        return {builder.mesh.vertices[base], builder.mesh.vertices[base + 1],
                builder.mesh.vertices[base + 2]};
    };
    const auto converges = [&](int rowA, int columnA, int rowB, int columnB) {
        const Vec3 a = vertexPosition(rowA, columnA);
        const Vec3 b = vertexPosition(rowB, columnB);
        const Vec3 d = sub(a, b);
        return dot(d, d) < 0.000001f;
    };

    const int lastRow = rows - 1;
    if (!path.open) {
        // Wrap normals on T.
        for (int column = 0; column < columns; ++column) {
            const Vec3 combined = add(vertexNormal(0, column), vertexNormal(lastRow, column));
            setVertexNormal(0, column, combined);
            setVertexNormal(lastRow, column, combined);
        }
    }
    const bool bottomConverges = rows >= 2 && converges(0, 0, rows - 2, 0);
    const bool topConverges = rows >= 2 && converges(0, columns - 1, rows - 2, columns - 1);
    if (!profile.open && !bottomConverges) {
        // Wrap normals on S.
        for (int row = 0; row < rows; ++row) {
            const Vec3 combined = add(vertexNormal(row, 0), vertexNormal(row, columns - 1));
            setVertexNormal(row, 0, combined);
            setVertexNormal(row, columns - 1, combined);
        }
    }
    if (path.pathType == kPathCircle && profile.profileType == kProfileCircleHalf) {
        if (bottomConverges) {
            for (int row = 0; row < rows; ++row) {
                setVertexNormal(row, 0, {1.f, 0.f, 0.f});
            }
        }
        if (topConverges) {
            for (int row = 0; row < rows; ++row) {
                setVertexNormal(row, columns - 1, {-1.f, 0.f, 0.f});
            }
        }
    }
}

// The viewer's hollow cap triangulation: walk inwards from both ends of the
// ring and pick the diagonal that keeps the triangle areas positive, so that
// concave rings stay correct. Areas are computed on the untransformed profile
// points, exactly like LLVolumeFace::createCap.
std::vector<int> ringTriangles(const std::vector<ProfilePoint>& points, int totalOut) {
    std::vector<int> triangles;
    const int count = static_cast<int>(points.size());
    if (count < 3 || totalOut <= 0) {
        return triangles;
    }
    int pt1 = 0;
    int pt2 = count - 1;
    while (pt2 - pt1 > 1) {
        const ProfilePoint& p1 = points[static_cast<size_t>(pt1)];
        const ProfilePoint& p2 = points[static_cast<size_t>(pt2)];
        const ProfilePoint& pa = points[static_cast<size_t>(pt1 + 1)];
        const ProfilePoint& pb = points[static_cast<size_t>(pt2 - 1)];
        const float area1a2 =
                (p1.x * pa.y - pa.x * p1.y) + (pa.x * p2.y - p2.x * pa.y) + (p2.x * p1.y - p1.x * p2.y);
        const float area1ba =
                (p1.x * pb.y - pb.x * p1.y) + (pb.x * pa.y - pa.x * pb.y) + (pa.x * p1.y - p1.x * pa.y);
        const float area21b =
                (p2.x * p1.y - p1.x * p2.y) + (p1.x * pb.y - pb.x * p1.y) + (pb.x * p2.y - p2.x * pb.y);
        const float area2ab =
                (p2.x * pa.y - pa.x * p2.y) + (pa.x * pb.y - pb.x * pa.y) + (pb.x * p2.y - p2.x * pb.y);
        bool tri1a2 = true;
        bool tri21b = true;
        if (area1a2 < 0.f) {
            tri1a2 = false;
        }
        if (area2ab < 0.f) {
            tri1a2 = false;
        }
        if (area21b < 0.f) {
            tri21b = false;
        }
        if (area1ba < 0.f) {
            tri21b = false;
        }
        bool useTri1a2 = true;
        if (!tri1a2) {
            useTri1a2 = false;
        } else if (!tri21b) {
            useTri1a2 = true;
        } else {
            const float d1x = p1.x - pa.x;
            const float d1y = p1.y - pa.y;
            const float d2x = p2.x - pb.x;
            const float d2y = p2.y - pb.y;
            useTri1a2 = (d1x * d1x + d1y * d1y) < (d2x * d2x + d2y * d2y);
        }
        if (useTri1a2) {
            triangles.push_back(pt1);
            triangles.push_back(pt1 + 1);
            triangles.push_back(pt2);
            ++pt1;
        } else {
            triangles.push_back(pt1);
            triangles.push_back(pt2 - 1);
            triangles.push_back(pt2);
            --pt2;
        }
    }
    return triangles;
}

// LLVolumeFace::createCap(): a fan from the cap centre, or, when the primitive
// is hollow, a ring between the outer and the reversed inner profile ring.
void emitCap(Builder& builder, const Profile& profile, const Path& path, bool atBegin) {
    const int total = static_cast<int>(profile.points.size());
    const int rows = static_cast<int>(path.points.size());
    if (total < 3 || rows < 2) {
        return;
    }
    // The viewer calls the path-begin cap "top" and builds it from the last
    // path row, and the path-end cap from the first (LLVolumeFace::createCap).
    const int row = atBegin ? rows - 1 : 0;
    const PathPoint& pathPoint = path.points[static_cast<size_t>(row)];
    const auto capU = [&](const ProfilePoint& point) { return point.x + 0.5f; };
    const auto capV = [&](const ProfilePoint& point) {
        return atBegin ? point.y + 0.5f : 0.5f - point.y;
    };

    if (!profile.hollow) {
        // The viewer fans over every profile point plus a centre vertex; for a
        // cut profile the appended centre point is itself the fan apex.
        int ring = total;
        int apexIndex = -1;
        if (profile.open) {
            apexIndex = total - 1;
            ring = total - 1;
        }
        if (ring < 3) {
            return;
        }
        float minU = capU(profile.points[0]);
        float maxU = minU;
        float minV = capV(profile.points[0]);
        float maxV = minV;
        Vec3 minPos = sweep(pathPoint, profile.points[0]);
        Vec3 maxPos = minPos;
        for (int i = 1; i < ring; ++i) {
            const ProfilePoint& point = profile.points[static_cast<size_t>(i)];
            minU = std::min(minU, capU(point));
            maxU = std::max(maxU, capU(point));
            minV = std::min(minV, capV(point));
            maxV = std::max(maxV, capV(point));
            const Vec3 position = sweep(pathPoint, point);
            minPos = {std::min(minPos.x, position.x), std::min(minPos.y, position.y),
                      std::min(minPos.z, position.z)};
            maxPos = {std::max(maxPos.x, position.x), std::max(maxPos.y, position.y),
                      std::max(maxPos.z, position.z)};
        }
        const float centreU = (minU + maxU) * 0.5f;
        const float centreV = (minV + maxV) * 0.5f;
        const Vec3 apex = apexIndex >= 0
                                  ? sweep(pathPoint, profile.points[static_cast<size_t>(apexIndex)])
                                  : Vec3{(minPos.x + maxPos.x) * 0.5f, (minPos.y + maxPos.y) * 0.5f,
                                         (minPos.z + maxPos.z) * 0.5f};
        const int apexVertex = builder.addVertex(apex, centreU, centreV);
        std::vector<int> ringVertices(static_cast<size_t>(ring));
        for (int i = 0; i < ring; ++i) {
            const ProfilePoint& point = profile.points[static_cast<size_t>(i)];
            ringVertices[static_cast<size_t>(i)] =
                    builder.addVertex(sweep(pathPoint, point), capU(point), capV(point));
        }
        for (int i = 0; i + 1 < ring; ++i) {
            const int current = ringVertices[static_cast<size_t>(i)];
            const int next = ringVertices[static_cast<size_t>(i + 1)];
            if (atBegin) {
                builder.addTriangle(apexVertex, current, next);
            } else {
                builder.addTriangle(apexVertex, next, current);
            }
        }
        return;
    }

    const std::vector<int> triangles = ringTriangles(profile.points, profile.totalOut);
    std::vector<int> cache(static_cast<size_t>(total), -1);
    const auto resolve = [&](int index) {
        int& slot = cache[static_cast<size_t>(index)];
        if (slot < 0) {
            const ProfilePoint& point = profile.points[static_cast<size_t>(index)];
            slot = builder.addVertex(sweep(pathPoint, point), capU(point), capV(point));
        }
        return slot;
    };
    for (size_t i = 0; i + 2 < triangles.size(); i += 3) {
        const int a = triangles[i];
        const int b = triangles[i + 1];
        const int c = triangles[i + 2];
        if (atBegin) {
            builder.addTriangle(resolve(a), resolve(b), resolve(c));
        } else {
            builder.addTriangle(resolve(a), resolve(c), resolve(b));
        }
    }
}

void finaliseNormals(MeshData& mesh) {
    for (size_t i = 0; i + 7 < mesh.vertices.size(); i += 8) {
        Vec3 n{mesh.vertices[i + 3], mesh.vertices[i + 4], mesh.vertices[i + 5]};
        if (length(n) < 1e-9f) {
            n = {0.f, 0.f, 1.f};
        } else {
            n = normalise(n);
        }
        mesh.vertices[i + 3] = n.x;
        mesh.vertices[i + 4] = n.y;
        mesh.vertices[i + 5] = n.z;
    }
}

void computeBounds(MeshData& mesh) {
    if (mesh.vertices.empty()) {
        for (int i = 0; i < 3; ++i) {
            mesh.boundsMin[i] = 0.f;
            mesh.boundsMax[i] = 0.f;
        }
        return;
    }
    for (int i = 0; i < 3; ++i) {
        mesh.boundsMin[i] = mesh.vertices[static_cast<size_t>(i)];
        mesh.boundsMax[i] = mesh.vertices[static_cast<size_t>(i)];
    }
    for (size_t i = 0; i + 7 < mesh.vertices.size(); i += 8) {
        for (int axis = 0; axis < 3; ++axis) {
            const float value = mesh.vertices[i + static_cast<size_t>(axis)];
            mesh.boundsMin[axis] = std::min(mesh.boundsMin[axis], value);
            mesh.boundsMax[axis] = std::max(mesh.boundsMax[axis], value);
        }
    }
}

}  // namespace

MeshData buildPrimMesh(const PrimParams& raw) {
    const PrimF p = convert(raw);
    const Path path = buildPath(p);
    if (!path.valid) {
        return MeshData();
    }
    const Profile profile = buildProfile(p);
    if (profile.points.size() < 2) {
        return MeshData();
    }

    Builder builder;
    int faceIndex = 0;

    // Emitted in exactly the order LLProfile::generate() builds its face list.
    if (path.open) {
        builder.beginFace(faceIndex++);
        emitCap(builder, profile, path, true);
        builder.endFace();
    }
    for (const Face& face : profile.sideFaces) {
        builder.beginFace(faceIndex++);
        emitSide(builder, profile, path, face, false);
        builder.endFace();
    }
    if (profile.hollow) {
        builder.beginFace(faceIndex++);
        emitSide(builder, profile, path, profile.innerFace, profile.innerFlat);
        builder.endFace();
    }
    if (path.open) {
        builder.beginFace(faceIndex++);
        emitCap(builder, profile, path, false);
        builder.endFace();
    }
    for (const Face& face : profile.walls) {
        builder.beginFace(faceIndex++);
        emitSide(builder, profile, path, face, true);
        builder.endFace();
    }

    builder.mesh.faces = builder.faces;
    finaliseNormals(builder.mesh);
    computeBounds(builder.mesh);
    return builder.mesh;
}

// The viewer renders PCODE_GRASS and PCODE_LEGACY_TREE with dedicated classes
// (billboards cross-faded against a grass/tree texture) rather than with the
// profile/path sweep. These two stand-ins keep those objects visible; they are
// not simulated world data and are only used when a simulator actually sends
// those pcodes.
// 2.26: V en convenio oficial (0 abajo, 1 arriba, como createSide/createCap):
// el shader convierte a espacio de muestreo Filament (fila superior primero).
// Antes estas mallas fijas usaban V invertida respecto al resto de fuentes.
MeshData buildGrassMesh() {
    Builder builder;
    // One face group for the three billboard quads. A mesh without a face group
    // has `faceCount == 0`, and the renderer indexes groups by face: that is
    // what produced "length=0; index=-2" for every grass/tree object. Every mesh
    // this file returns must carry at least one group.
    builder.beginFace(0);
    for (int i = 0; i < 3; ++i) {
        const float angle = static_cast<float>(i) * (kPi / 3.f);
        const float dx = std::cos(angle) * 0.5f;
        const float dy = std::sin(angle) * 0.5f;
        const float nx = -std::sin(angle);
        const float ny = std::cos(angle);
        const int a = builder.addVertex({-dx, -dy, -0.5f}, 0.f, 0.f);
        const int b = builder.addVertex({dx, dy, -0.5f}, 1.f, 0.f);
        const int c = builder.addVertex({dx, dy, 0.5f}, 1.f, 1.f);
        const int d = builder.addVertex({-dx, -dy, 0.5f}, 0.f, 1.f);
        builder.addQuad(a, b, c, d);
        (void)nx;
        (void)ny;
    }
    builder.endFace();
    builder.mesh.faces = builder.faces;
    finaliseNormals(builder.mesh);
    computeBounds(builder.mesh);
    return builder.mesh;
}

MeshData buildTreeMesh(int variant) {
    Builder builder;
    const float trunk = 0.42f;
    const float trunkRadius = 0.06f;
    const int sides = 6;
    // Face 0 is the trunk, face 1 the canopy: the same two faces the viewer's
    // legacy tree uses for its two textures, so per-face materials can tell them
    // apart later. Both are always present.
    builder.beginFace(0);
    for (int i = 0; i < sides; ++i) {
        const float a0 = 2.f * kPi * static_cast<float>(i) / static_cast<float>(sides);
        const float a1 = 2.f * kPi * static_cast<float>(i + 1) / static_cast<float>(sides);
        const int t0 = builder.addVertex({std::cos(a0) * trunkRadius, std::sin(a0) * trunkRadius,
                                          -0.5f},
                                         static_cast<float>(i) / static_cast<float>(sides), 0.f);
        const int t1 = builder.addVertex({std::cos(a1) * trunkRadius, std::sin(a1) * trunkRadius,
                                          -0.5f},
                                         static_cast<float>(i + 1) / static_cast<float>(sides), 0.f);
        const int t2 = builder.addVertex({std::cos(a1) * trunkRadius, std::sin(a1) * trunkRadius,
                                          -0.5f + trunk},
                                         static_cast<float>(i + 1) / static_cast<float>(sides), 1.f);
        const int t3 = builder.addVertex({std::cos(a0) * trunkRadius, std::sin(a0) * trunkRadius,
                                          -0.5f + trunk},
                                         static_cast<float>(i) / static_cast<float>(sides), 1.f);
        builder.addQuad(t0, t1, t2, t3);
    }
    builder.endFace();
    builder.beginFace(1);
    const float canopyCentre = -0.5f + trunk + (0.5f - trunk) * 0.45f;
    const float canopyRadius = 0.34f + 0.04f * static_cast<float>(variant);
    const int billboards = 3;
    for (int i = 0; i < billboards; ++i) {
        const float angle = static_cast<float>(i) * (kPi / static_cast<float>(billboards));
        const float dx = std::cos(angle) * canopyRadius;
        const float dy = std::sin(angle) * canopyRadius;
        const int a = builder.addVertex({-dx, -dy, canopyCentre - canopyRadius * 0.75f}, 0.f, 0.f);
        const int b = builder.addVertex({dx, dy, canopyCentre - canopyRadius * 0.75f}, 1.f, 0.f);
        const int c = builder.addVertex({dx, dy, canopyCentre + canopyRadius * 0.9f}, 1.f, 1.f);
        const int d = builder.addVertex({-dx, -dy, canopyCentre + canopyRadius * 0.9f}, 0.f, 1.f);
        builder.addQuad(a, b, c, d);
    }
    builder.endFace();
    builder.mesh.faces = builder.faces;
    finaliseNormals(builder.mesh);
    computeBounds(builder.mesh);
    return builder.mesh;
}

}  // namespace slcore
