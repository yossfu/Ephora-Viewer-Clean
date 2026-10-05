// Standalone checks for sl_prim_geometry.cpp.
//
// Build:  cmake -DSLCORE_BUILD_TESTS=ON && ctest   (or: g++ -std=c++17
//         slcore_tests.cpp sl_prim_geometry.cpp -o slcore_tests && ./slcore_tests)
//
// These verify the invariants that matter for the renderer: the number of faces
// per shape (Second Life face numbering), an outward-consistent winding
// (checked through the divergence-theorem volume, which is positive only when
// every triangle faces outwards), unit normals, in-range indices, and the
// local bounds the caller relies on.

#include "sl_prim_geometry.h"

#include <cmath>
#include <cstdint>
#include <cstdio>

namespace {

int g_failures = 0;

void check(bool condition, const char* name, const char* what) {
    if (!condition) {
        std::printf("FAIL  %-22s %s\n", name, what);
        ++g_failures;
    }
}

slcore::PrimParams baseParams() {
    slcore::PrimParams params;
    params.pathCurve = 0x10;      // LL_PCODE_PATH_LINE
    params.profileCurve = 0x01;   // LL_PCODE_PROFILE_SQUARE
    params.pathScaleX = 100;
    params.pathScaleY = 100;
    params.detail = 3;
    return params;
}

void setProfileEnd(slcore::PrimParams& params, float end) {
    // profileEnd wire value: end = 1 - wire * 0.00002
    params.profileEnd = static_cast<int>(std::lround((1.f - end) / 0.00002f));
}

void setProfileBegin(slcore::PrimParams& params, float begin) {
    params.profileBegin = static_cast<int>(std::lround(begin / 0.00002f));
}

double signedVolume(const slcore::MeshData& mesh) {
    double volume = 0.0;
    for (size_t i = 0; i + 2 < mesh.indices.size(); i += 3) {
        const size_t a = static_cast<size_t>(mesh.indices[i]) * 8;
        const size_t b = static_cast<size_t>(mesh.indices[i + 1]) * 8;
        const size_t c = static_cast<size_t>(mesh.indices[i + 2]) * 8;
        const double ax = mesh.vertices[a], ay = mesh.vertices[a + 1], az = mesh.vertices[a + 2];
        const double bx = mesh.vertices[b], by = mesh.vertices[b + 1], bz = mesh.vertices[b + 2];
        const double cx = mesh.vertices[c], cy = mesh.vertices[c + 1], cz = mesh.vertices[c + 2];
        volume += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
    }
    return volume / 6.0;
}

void checkMesh(const char* name, const slcore::MeshData& mesh) {
    const size_t vertexCount = mesh.vertices.size() / 8;
    check(vertexCount > 0, name, "has vertices");
    check(mesh.indices.size() % 3 == 0, name, "index count is a multiple of three");
    bool indicesInRange = true;
    for (uint32_t index : mesh.indices) {
        if (index >= vertexCount) {
            indicesInRange = false;
        }
    }
    check(indicesInRange, name, "every index is inside the vertex buffer");
    bool normalsUnit = true;
    bool finite = true;
    for (size_t i = 0; i + 7 < mesh.vertices.size(); i += 8) {
        const double nx = mesh.vertices[i + 3];
        const double ny = mesh.vertices[i + 4];
        const double nz = mesh.vertices[i + 5];
        const double len = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (std::fabs(len - 1.0) > 1e-3) {
            normalsUnit = false;
        }
        for (int axis = 0; axis < 8; ++axis) {
            if (!std::isfinite(mesh.vertices[i + static_cast<size_t>(axis)])) {
                finite = false;
            }
        }
    }
    check(normalsUnit, name, "normals are unit length");
    check(finite, name, "no NaN or infinity");
    check(mesh.triangleCount() > 0, name, "has triangles");
    check(mesh.faces.size() > 0, name, "has face groups");
    uint32_t total = 0;
    for (const slcore::FaceGroup& face : mesh.faces) {
        total += face.indexCount;
    }
    check(total == mesh.indexCount(), name, "face groups cover every index");
}

void checkFaces(const char* name, const slcore::MeshData& mesh, size_t expected) {
    if (mesh.faces.size() != expected) {
        std::printf("FAIL  %-22s face count %zu, expected %zu\n", name, mesh.faces.size(), expected);
        ++g_failures;
    }
}

void checkVolume(const char* name, const slcore::MeshData& mesh, double expected, double tolerance) {
    const double volume = signedVolume(mesh);
    if (!(volume > 0.0) || std::fabs(volume - expected) > tolerance) {
        std::printf("FAIL  %-22s volume %.4f, expected %.4f (+/- %.4f)\n", name, volume, expected,
                    tolerance);
        ++g_failures;
    }
}

void checkBounds(const char* name, const slcore::MeshData& mesh, float extent, float tolerance) {
    for (int axis = 0; axis < 3; ++axis) {
        if (std::fabs(mesh.boundsMin[axis] + extent) > tolerance ||
            std::fabs(mesh.boundsMax[axis] - extent) > tolerance) {
            std::printf("FAIL  %-22s bounds axis %d = [%.4f, %.4f], expected +/-%.4f\n", name, axis,
                        mesh.boundsMin[axis], mesh.boundsMax[axis], extent);
            ++g_failures;
        }
    }
}

void checkOutward(const char* name, const slcore::MeshData& mesh, bool radial) {
    bool outward = true;
    for (size_t i = 0; i + 7 < mesh.vertices.size(); i += 8) {
        const double px = mesh.vertices[i], py = mesh.vertices[i + 1], pz = mesh.vertices[i + 2];
        const double nx = mesh.vertices[i + 3], ny = mesh.vertices[i + 4], nz = mesh.vertices[i + 5];
        if (radial && std::fabs(nz) > 0.7) {
            continue;  // caps are not radial
        }
        double dx = px;
        double dy = py;
        double dz = pz;
        if (radial) {
            dz = 0.0;
        }
        const double len = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) {
            continue;
        }
        const double dot = (nx * dx + ny * dy + nz * dz) / len;
        if (dot < 0.05) {
            outward = false;
        }
    }
    check(outward, name, radial ? "normals point away from the axis" : "normals point outwards");
}

void testBox() {
    const slcore::MeshData mesh = slcore::buildPrimMesh(baseParams());
    checkMesh("box", mesh);
    checkFaces("box", mesh, 6);
    checkVolume("box", mesh, 1.0, 1e-3);
    checkBounds("box", mesh, 0.5f, 1e-3f);
}

void testCylinder() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;  // LL_PCODE_PROFILE_CIRCLE
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("cylinder", mesh);
    checkFaces("cylinder", mesh, 3);
    // 18-gon cross section with circumradius 0.5, 1 unit tall.
    checkVolume("cylinder", mesh, 0.5 * 18.0 * 0.25 * std::sin(2.0 * 3.14159265 / 18.0), 1e-3);
    checkOutward("cylinder", mesh, true);
}

void testSphere() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x05;  // LL_PCODE_PROFILE_CIRCLE_HALF
    params.pathCurve = 0x20;     // LL_PCODE_PATH_CIRCLE
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("sphere", mesh);
    checkFaces("sphere", mesh, 1);
    checkVolume("sphere", mesh, 4.0 / 3.0 * 3.14159265 * 0.125, 0.05);
    checkBounds("sphere", mesh, 0.5f, 0.02f);
    checkOutward("sphere", mesh, false);
}

void testCutCylinder() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;
    setProfileBegin(params, 0.5f);
    setProfileEnd(params, 1.f);
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("cut cylinder", mesh);
    checkFaces("cut cylinder", mesh, 5);
    checkVolume("cut cylinder", mesh, 0.5 * 0.5 * 18.0 * 0.25 * std::sin(2.0 * 3.14159265 / 18.0),
                1e-3);
}

void testHollowTube() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;
    params.profileHollow = 25000;  // 0.5
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("hollow tube", mesh);
    checkFaces("hollow tube", mesh, 4);
    const double outer = 0.5 * 18.0 * 0.25 * std::sin(2.0 * 3.14159265 / 18.0);
    const double inner = 0.5 * 18.0 * 0.0625 * std::sin(2.0 * 3.14159265 / 18.0);
    checkVolume("hollow tube", mesh, outer - inner, 1e-3);
}

void testHollowBox() {
    slcore::PrimParams params = baseParams();
    params.profileHollow = 25000;
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("hollow box", mesh);
    checkFaces("hollow box", mesh, 7);
    checkVolume("hollow box", mesh, 0.75, 1e-3);
}

void testCutBox() {
    slcore::PrimParams params = baseParams();
    setProfileEnd(params, 0.75f);
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("cut box", mesh);
    checkFaces("cut box", mesh, 7);
    checkVolume("cut box", mesh, 0.75, 1e-3);
}

void testPrism() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x03;  // LL_PCODE_PROFILE_EQUALTRI
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("prism", mesh);
    checkFaces("prism", mesh, 5);
    checkVolume("prism", mesh, 0.5 * 0.8660254 * 0.75, 1e-3);
}

void testTorus() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;
    params.pathCurve = 0x20;   // LL_PCODE_PATH_CIRCLE
    params.pathScaleX = 175;   // path radius 0.375, tube radius 0.125
    params.pathScaleY = 175;
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("torus", mesh);
    checkFaces("torus", mesh, 1);
    // 2 * pi^2 * R * r^2 with R = 0.375, r = 0.125 (polygon approximation).
    checkVolume("torus", mesh, 2.0 * 3.14159265 * 3.14159265 * 0.375 * 0.015625, 0.01);
}

void testCircle2Path() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;
    params.pathCurve = 0x30;  // LL_PCODE_PATH_CIRCLE2
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("circle2 path", mesh);
    checkFaces("circle2 path", mesh, 1);
}

void testSquashedBox() {
    slcore::PrimParams params = baseParams();
    params.pathScaleX = 50;  // path scale 1.5, so the ends taper inwards
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    checkMesh("tapered box", mesh);
    checkVolume("tapered box", mesh, 0.75, 0.02);
}

void testDegenerate() {
    slcore::PrimParams params = baseParams();
    params.profileCurve = 0x00;  // a circular profile needs 6 * detail sides
    params.detail = 0;
    const slcore::MeshData mesh = slcore::buildPrimMesh(params);
    check(mesh.vertices.empty(), "detail 0", "produces no geometry rather than garbage");
}

// The legacy grass/tree pcodes get fixed meshes instead of a path/profile
// sweep. They used to be assembled without calling beginFace/endFace, so their
// MeshData carried zero face groups — and the renderer, which indexes face
// groups by face index, looked up `faceGroups[-2]` and threw
// "length=0; index=-2" once per object. Every mesh that leaves this file must
// carry at least one face group whose ranges cover the whole index buffer;
// checkMesh() asserts the coverage, checkFaces() the exact count.
void testLegacyFixedMeshes() {
    const slcore::MeshData grass = slcore::buildGrassMesh();
    checkMesh("grass", grass);
    checkFaces("grass", grass, 1);

    for (int variant = 0; variant < 3; ++variant) {
        const slcore::MeshData tree = slcore::buildTreeMesh(variant);
        checkMesh("tree", tree);
        checkFaces("tree", tree, 2);
    }
}

}  // namespace

int main() {
    testBox();
    testCylinder();
    testSphere();
    testCutCylinder();
    testHollowTube();
    testHollowBox();
    testCutBox();
    testPrism();
    testTorus();
    testCircle2Path();
    testSquashedBox();
    testDegenerate();
    testLegacyFixedMeshes();
    if (g_failures == 0) {
        std::printf("all sl_prim_geometry checks passed\n");
        return 0;
    }
    std::printf("%d check(s) failed\n", g_failures);
    return 1;
}
