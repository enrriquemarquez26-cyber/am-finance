package com.balanceone.nativeapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun rememberHomeTilt(enabled:Boolean):State<Float>{
    val tilt=remember {mutableFloatStateOf(0f)}
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    DisposableEffect(enabled,owner,context){
        val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor=if(enabled) manager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null
        var origin:Float?=null
        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
            override fun onSensorChanged(event:SensorEvent){
                val x=event.values[0]
                val base=origin ?: x.also {origin=it}
                tilt.floatValue=((x-base)/8f).coerceIn(-1f,1f)
            }
        }
        fun stop(){manager.unregisterListener(listener);origin=null;tilt.floatValue=0f}
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME && sensor!=null) manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)
            if(event==Lifecycle.Event.ON_PAUSE)stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);stop()}
    }
    return tilt
}

@Composable
internal fun Modifier.pullToAdd(list:LazyListState,enabled:Boolean,open:()->Unit):Modifier{
    if(!enabled)return this
    val callback by rememberUpdatedState(open)
    val threshold=with(LocalDensity.current){84.dp.toPx()}
    val connection=remember(list,threshold){object:NestedScrollConnection{
        var distance=0f
        var armed=true
        override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
            if(source!=NestedScrollSource.UserInput)return Offset.Zero
            if(list.canScrollBackward || consumed.y<0 || available.y<0){distance=0f;return Offset.Zero}
            if(armed){
                distance+=available.y.coerceAtLeast(0f)
                if(distance>=threshold){armed=false;callback()}
            }
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed:Velocity,available:Velocity):Velocity{distance=0f;armed=true;return Velocity.Zero}
    }}
    return nestedScroll(connection)
}
