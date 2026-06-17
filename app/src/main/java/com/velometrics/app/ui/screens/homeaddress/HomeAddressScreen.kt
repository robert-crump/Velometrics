package com.velometrics.app.ui.screens.homeaddress

import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.ComposableMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAddressScreen(
    onBack: () -> Unit,
    viewModel: HomeAddressViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lat by viewModel.lat.collectAsState()
    val lon by viewModel.lon.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    // Hold references to live map objects for pin updates
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }

    // Get last known GPS/network location
    LaunchedEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                currentLocation = LatLng(loc.latitude, loc.longitude)
            }
        } catch (_: SecurityException) { }
    }

    // Show current location dot on map and fly camera there
    LaunchedEffect(currentLocation, styleRef) {
        val style = styleRef ?: return@LaunchedEffect
        val loc = currentLocation ?: return@LaunchedEffect
        val point = Point.fromLngLat(loc.longitude, loc.latitude)
        val fc = FeatureCollection.fromFeature(Feature.fromGeometry(point))
        val locSource = style.getSource("location-source") as? GeoJsonSource
        locSource?.setGeoJson(fc)
        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 15.0))
    }

    // Update pin on map whenever lat/lon changes
    LaunchedEffect(lat, lon) {
        val style = styleRef ?: return@LaunchedEffect
        val source = style.getSource("home-pin-source") as? GeoJsonSource ?: return@LaunchedEffect
        val point = Point.fromLngLat(lon, lat)
        source.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(point)))
        mapRef?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
    }

    // Local text field states (separate from parsed doubles so user can type freely)
    var latText by remember(lat) { mutableStateOf("%.6f".format(lat)) }
    var lonText by remember(lon) { mutableStateOf("%.6f".format(lon)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Home Location") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onBack) }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Address search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search address…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            )

            // Search results dropdown
            if (searchResults.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(searchResults) { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val rLat = result.lat.toDoubleOrNull() ?: return@clickable
                                        val rLon = result.lon.toDoubleOrNull() ?: return@clickable
                                        viewModel.selectLocation(rLat, rLon, result.displayName)
                                        viewModel.updateSearchQuery("")
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = result.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Map — fills remaining space
            Box(modifier = Modifier.weight(1f)) {
                ComposableMapView(
                    modifier = Modifier.fillMaxSize(),
                    initialCenter = LatLng(lat, lon),
                    initialZoom = 14.0,
                    onMapReady = { map, style ->
                        mapRef = map
                        styleRef = style

                        // Add current location source + layers (below home pin)
                        style.addSource(GeoJsonSource("location-source", FeatureCollection.fromFeatures(listOf())))
                        style.addLayer(CircleLayer("location-accuracy-layer", "location-source").withProperties(
                            PropertyFactory.circleColor("#42A5F5"),
                            PropertyFactory.circleRadius(60f),
                            PropertyFactory.circleOpacity(0.18f),
                            PropertyFactory.circleStrokeWidth(0f)
                        ))
                        style.addLayer(CircleLayer("location-dot-layer", "location-source").withProperties(
                            PropertyFactory.circleColor("#0D47A1"),
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2f)
                        ))

                        // Add initial pin source + layer
                        val point = Point.fromLngLat(lon, lat)
                        val source = GeoJsonSource(
                            "home-pin-source",
                            FeatureCollection.fromFeature(Feature.fromGeometry(point))
                        )
                        style.addSource(source)
                        val layer = CircleLayer("home-pin-layer", "home-pin-source").withProperties(
                            PropertyFactory.circleColor("#F44336"),
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2.5f)
                        )
                        style.addLayer(layer)

                        // Tap to move pin
                        map.addOnMapClickListener { latLng ->
                            viewModel.selectLocation(latLng.latitude, latLng.longitude)
                            true
                        }
                    }
                )
            }

            // Lat/lon manual input at the bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it; viewModel.updateLatField(it) },
                    label = { Text("Latitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it; viewModel.updateLonField(it) },
                    label = { Text("Longitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
