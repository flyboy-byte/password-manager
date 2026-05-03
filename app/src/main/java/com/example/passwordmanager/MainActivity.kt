package com.example.passwordmanager

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.passwordmanager.ui.AddPasswordScreen
import com.example.passwordmanager.ui.VaultScreen
import com.example.passwordmanager.ui.theme.PasswordManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and screen recording for security
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        
        enableEdgeToEdge()
        setContent {
            PasswordManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "vault") {
                        composable("vault") {
                            VaultScreen(
                                onNavigateToAdd = { navController.navigate("add") },
                                onNavigateToEdit = { id -> navController.navigate("add?id=$id") }
                            )
                        }
                        composable(
                            route = "add?id={id}",
                            arguments = listOf(
                                navArgument("id") {
                                    type = NavType.IntType
                                    defaultValue = -1
                                }
                            )
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: -1
                            AddPasswordScreen(
                                passwordId = if (id == -1) null else id,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}