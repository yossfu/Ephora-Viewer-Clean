// JNI bridge between the Kotlin scene layer and the native primitive geometry.
//
// The Kotlin side passes the primitive's raw protocol values (exactly the
// ObjectUpdate fields, no conversion) and gets back the mesh: vertices
// (position, normal, uv interleaved), indices and the Second Life face groups.

#include <jni.h>

#include <vector>

#include "sl_prim_geometry.h"

namespace {

jobjectArray buildMeshArray(JNIEnv* env, const slcore::MeshData& mesh) {
    if (mesh.vertices.empty() || mesh.indices.empty()) {
        return nullptr;
    }
    const jsize vertexCount = static_cast<jsize>(mesh.vertices.size());
    jfloatArray vertices = env->NewFloatArray(vertexCount);
    if (vertices == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(vertices, 0, vertexCount, mesh.vertices.data());

    const jsize indexCount = static_cast<jsize>(mesh.indices.size());
    jintArray indices = env->NewIntArray(indexCount);
    if (indices == nullptr) {
        return nullptr;
    }
    std::vector<jint> indexBuffer(mesh.indices.size());
    for (size_t i = 0; i < mesh.indices.size(); ++i) {
        indexBuffer[i] = static_cast<jint>(mesh.indices[i]);
    }
    env->SetIntArrayRegion(indices, 0, indexCount, indexBuffer.data());

    const jsize faceCount = static_cast<jsize>(mesh.faces.size() * 3);
    jintArray faceGroups = env->NewIntArray(faceCount);
    if (faceGroups == nullptr) {
        return nullptr;
    }
    std::vector<jint> faceBuffer(mesh.faces.size() * 3);
    for (size_t i = 0; i < mesh.faces.size(); ++i) {
        faceBuffer[i * 3] = mesh.faces[i].faceIndex;
        faceBuffer[i * 3 + 1] = static_cast<jint>(mesh.faces[i].firstIndex);
        faceBuffer[i * 3 + 2] = static_cast<jint>(mesh.faces[i].indexCount);
    }
    env->SetIntArrayRegion(faceGroups, 0, faceCount, faceBuffer.data());

    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetObjectArrayElement(result, 0, vertices);
    env->SetObjectArrayElement(result, 1, indices);
    env->SetObjectArrayElement(result, 2, faceGroups);
    env->DeleteLocalRef(vertices);
    env->DeleteLocalRef(indices);
    env->DeleteLocalRef(faceGroups);
    env->DeleteLocalRef(objectClass);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lumiyaviewer_lumiya_slscene_PrimGeometryNative_nativeBuildPrimMesh(JNIEnv* env, jclass,
                                                                           jintArray packed) {
    if (packed == nullptr || env->GetArrayLength(packed) < 19) {
        return nullptr;
    }
    jint values[19] = {0};
    env->GetIntArrayRegion(packed, 0, 19, values);
    slcore::PrimParams params;
    params.pathCurve = values[0];
    params.profileCurve = values[1];
    params.pathBegin = values[2];
    params.pathEnd = values[3];
    params.pathScaleX = values[4];
    params.pathScaleY = values[5];
    params.pathShearX = values[6];
    params.pathShearY = values[7];
    params.pathTwist = values[8];
    params.pathTwistBegin = values[9];
    params.pathRadiusOffset = values[10];
    params.pathTaperX = values[11];
    params.pathTaperY = values[12];
    params.pathRevolutions = values[13];
    params.pathSkew = values[14];
    params.profileBegin = values[15];
    params.profileEnd = values[16];
    params.profileHollow = values[17];
    params.detail = values[18];
    return buildMeshArray(env, slcore::buildPrimMesh(params));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lumiyaviewer_lumiya_slscene_PrimGeometryNative_nativeBuildFixedMesh(JNIEnv* env, jclass,
                                                                            jint kind) {
    // 0 = legacy grass, 1+ = legacy tree variant (kind - 1).
    if (kind <= 0) {
        return buildMeshArray(env, slcore::buildGrassMesh());
    }
    return buildMeshArray(env, slcore::buildTreeMesh(kind - 1));
}
