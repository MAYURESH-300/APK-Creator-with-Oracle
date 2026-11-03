//package com.example.environmenteditor.ui
//
//import androidx.compose.runtime.Composable
//import com.google.android.filament.Engine
//import com.google.android.filament.MaterialInstance
//import io.github.sceneview.math.Float3
////import com.google.android.filament.utils.Float3
//import io.github.sceneview.math.Position
//import io.github.sceneview.node.CubeNode
//import io.github.sceneview.node.SphereNode
//
///* ================= SELECTION GIZMO ================= */
//
//@Composable
//fun SelectionGizmo(
//    engine: Engine,
//    position: Position,
//    material: MaterialInstance,
//) {
//    val xAxis = CubeNode(
//        engine = engine,
//        size = Float3(1.2f, 0.025f, 0.025f),
//        materialInstance = material
//    )
//
//    xAxis.position = Position(
//        position.x + 0.6f,
//        position.y,
//        position.z
//    )
//
//    val yAxis = CubeNode(
//        engine = engine,
//        size = Float3(0.025f, 1.2f, 0.025f),
//        materialInstance = material
//    )
//
//    yAxis.position = Position(
//        position.x,
//        position.y + 0.6f,
//        position.z
//    )
//
//    val zAxis = CubeNode(
//        engine = engine,
//        size = Float3(0.025f, 0.025f, 1.2f),
//        materialInstance = material
//    )
//
//    zAxis.position = Position(
//        position.x,
//        position.y,
//        position.z + 0.6f
//    )
//
//    val center = SphereNode(
//        engine = engine,
//        radius = 0.08f,
//        materialInstance = material
//    )
//
//    center.position = position
//}