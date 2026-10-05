#ifndef SLCORE_SL_PRIM_GEOMETRY_H
#define SLCORE_SL_PRIM_GEOMETRY_H

#include <cstdint>
#include <vector>

namespace slcore {

// Raw primitive parameters exactly as they arrive on the wire (ObjectUpdate /
// ObjectUpdateCompressed). Nothing here is converted: the wire encoding is
// turned into the viewer's floating point form inside buildPrimMesh() using the
// same quanta as Linden Lab's LLPathParams / LLProfileParams constructors
// (indra/llmath/llvolume.h).
struct PrimParams {
    int pathCurve = 0x10;      // LL_PCODE_PATH_* in the high nibble
    int profileCurve = 0x01;   // LL_PCODE_PROFILE_* low nibble, hole in the high nibble
    int pathBegin = 0;         // U16
    int pathEnd = 0;           // U16
    int pathScaleX = 100;      // U8
    int pathScaleY = 100;      // U8
    int pathShearX = 0;        // U8, interpreted as S8
    int pathShearY = 0;        // U8, interpreted as S8
    int pathTwist = 0;         // U8, interpreted as S8
    int pathTwistBegin = 0;    // U8, interpreted as S8
    int pathRadiusOffset = 0;  // U8, interpreted as S8
    int pathTaperX = 0;        // U8, interpreted as S8
    int pathTaperY = 0;        // U8, interpreted as S8
    int pathRevolutions = 0;   // U8
    int pathSkew = 0;          // U8, interpreted as S8
    int profileBegin = 0;      // U16
    int profileEnd = 0;        // U16
    int profileHollow = 0;     // U16
    int detail = 3;            // tessellation detail, 1 (low) .. 4 (high)
};

// One TextureEntry face of the primitive. faceIndex is the Second Life face
// index: face 0 is the path-begin cap, then the outer side faces, then the
// hollow inner side, then the path-end cap, then the profile cut caps.
// See LLProfile::generate() in indra/llmath/llvolume.cpp.
struct FaceGroup {
    int faceIndex;
    uint32_t firstIndex;
    uint32_t indexCount;
};

// Interleaved vertex data: position(3), normal(3), uv(2).
struct MeshData {
    std::vector<float> vertices;
    std::vector<uint32_t> indices;
    std::vector<FaceGroup> faces;
    float boundsMin[3] = {0.f, 0.f, 0.f};
    float boundsMax[3] = {0.f, 0.f, 0.f};

    uint32_t vertexCount() const { return static_cast<uint32_t>(vertices.size() / 8); }
    uint32_t indexCount() const { return static_cast<uint32_t>(indices.size()); }
    uint32_t triangleCount() const { return static_cast<uint32_t>(indices.size() / 3); }
};

// Builds the primitive in the primitive's normalised local space: a box of
// scale (1,1,1) spans -0.5..0.5 on every axis, and the prim's own Scale is
// applied by the caller through the object transform, so identical shapes share
// one mesh.
MeshData buildPrimMesh(const PrimParams& params);

// Legacy non-prim pcodes (PCODE_GRASS, PCODE_LEGACY_TREE). These use the
// viewer's old fixed geometry rather than the profile/path sweep.
MeshData buildGrassMesh();
MeshData buildTreeMesh(int variant);

}  // namespace slcore

#endif  // SLCORE_SL_PRIM_GEOMETRY_H
