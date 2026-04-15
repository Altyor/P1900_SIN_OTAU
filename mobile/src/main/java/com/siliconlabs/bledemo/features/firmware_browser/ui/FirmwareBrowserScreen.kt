package com.siliconlabs.bledemo.features.firmware_browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siliconlabs.bledemo.features.firmware_browser.domain.CardType
import com.siliconlabs.bledemo.features.firmware_browser.domain.PnInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.ProductInfo
import com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
import com.siliconlabs.bledemo.features.firmware_browser.presentation.FirmwareBrowserUiState
import com.siliconlabs.bledemo.features.firmware_browser.presentation.FirmwareBrowserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareBrowserScreen(
    viewModel: FirmwareBrowserViewModel,
    onFinished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadProducts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(UiStrings.firmwareBrowserTitle) },
                navigationIcon = {
                    val state = uiState
                    if (state is FirmwareBrowserUiState.PnSelection ||
                        state is FirmwareBrowserUiState.CardSelection
                    ) {
                        IconButton(onClick = { viewModel.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = UiStrings.back
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is FirmwareBrowserUiState.Loading -> LoadingContent()
                is FirmwareBrowserUiState.ProductList -> ProductListContent(
                    products = state.products,
                    onProductSelected = { viewModel.selectProduct(it) }
                )
                is FirmwareBrowserUiState.PnSelection -> PnSelectionContent(
                    product = state.product,
                    pns = state.pns,
                    onPnSelected = { viewModel.selectPn(it) }
                )
                is FirmwareBrowserUiState.CardSelection -> CardSelectionContent(
                    product = state.product,
                    pn = state.pn,
                    hasAntenna = state.hasAntenna,
                    hasPower = state.hasPower,
                    onCardSelected = { viewModel.selectCard(it) }
                )
                is FirmwareBrowserUiState.Downloading -> DownloadingContent(
                    message = state.fileName
                )
                is FirmwareBrowserUiState.Ready -> {
                    ReadyContent(fileName = state.fileName)
                    LaunchedEffect(state) {
                        kotlinx.coroutines.delay(1500)
                        onFinished()
                    }
                }
                is FirmwareBrowserUiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(UiStrings.connecting)
        }
    }
}

@Composable
private fun ProductListContent(
    products: List<ProductInfo>,
    onProductSelected: (ProductInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = UiStrings.selectProduct,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp
            )
        ) {
            items(products) { product ->
                Card(
                    onClick = { onProductSelected(product) },
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        product.imagePath?.let { path ->
                            AsyncImage(
                                model = java.io.File(path),
                                contentDescription = product.name,
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .height(260.dp)
                                    .padding(top = 16.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(
                            text = product.name,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PnSelectionContent(
    product: ProductInfo,
    pns: List<PnInfo>,
    onPnSelected: (PnInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${product.name} - ${UiStrings.selectPartNumber}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp
            )
        ) {
            items(pns) { pn ->
                Card(
                    onClick = { onPnSelected(pn) },
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = pn.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CardSelectionContent(
    product: ProductInfo,
    pn: PnInfo,
    hasAntenna: Boolean,
    hasPower: Boolean,
    onCardSelected: (CardType) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = product.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "PN: ${pn.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = UiStrings.selectCardToUpdate,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                onClick = { if (hasAntenna) onCardSelected(CardType.ANTENNA) },
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (hasAntenna) 4.dp else 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasAntenna) MaterialTheme.colorScheme.primaryContainer
                        else Color(0xFFE0E0E0)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (hasAntenna) MaterialTheme.colorScheme.onPrimaryContainer
                        else Color(0xFF9E9E9E)
                    Icon(
                        Icons.Default.SettingsInputAntenna,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = tint
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = UiStrings.antenna,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = tint
                    )
                }
            }

            Card(
                onClick = { if (hasPower) onCardSelected(CardType.POWER) },
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (hasPower) 4.dp else 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasPower) MaterialTheme.colorScheme.secondaryContainer
                        else Color(0xFFE0E0E0)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (hasPower) MaterialTheme.colorScheme.onSecondaryContainer
                        else Color(0xFF9E9E9E)
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = tint
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = UiStrings.power,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = tint
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadingContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message)
        }
    }
}

@Composable
private fun ReadyContent(fileName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = UiStrings.firmwareReady,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(UiStrings.retry)
            }
        }
    }
}
