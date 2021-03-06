package com.ar.http;

import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.http.HttpException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;

import com.google.gson.Gson;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter.LengthFilter;
import android.util.Log;
import android.widget.Toast;

/*!
* @author Rohan Balakrishnan
* \class ConnectorService
* \brief Service class that checks which POIs are view based
* on GPS and compass readings, and transmits them back to the activity via intents
*/
public class ConnectorService extends Service {

	public static final String POI_IN_VIEW = "POI_IN_VIEW";
	private double latitude, longitude; /*!<Current geographic coordinates of the user's GPS according to the GPS hardware*/
	private float accuracy; /*!<Current accuracy of the user's GPS according to the GPS hardware*/
	private static float  phone_bearing; /*!<Current bearing of the phone*/
	private List<Data.POIFromObject> hotspots; /*!<Store the POI as they are received from the web-service*/
	private static float[] orientation_sensor = new float[3]; /*!<Store values received from orientation sensors*/
	private SensorManager sm; /*!<Access device sensor hardware programmatically*/
	LocationManager locationManager; /*!<Provides access to location services*/
	Location location; /*!<Current location of the user*/
	
	final int FIELD_OF_VIEW = 90; /*!<Camera field of view size*/
	float degree_to_pixel; /*!<Current location of the user*/
	public static boolean ChangeInModeToLandscape = false; /*!<Keeps track of whether of the phone is being viewed in portrait or landscape mode*/
	private static float Direction; 
	private static int Deviation = 90;
	private Timer updatetimer;

	private static Location CurrentLocation;
	private static boolean LocationChanged=false;
	
	@Override
	public IBinder onBind(Intent arg0) {

		return null;
	}

	/*
	 * @author Rohan Balakrishnan
	 * @return void
	 * 
	 * \brief This method is called whenever the service is called explicitly by the activity to
	 * serve as a deconstructor.
	 */
	
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		sm.unregisterListener(listener);
		locationManager.removeUpdates(locationListener);
		updatetimer.cancel();
		Toast.makeText(this, "Service destroyed ...", Toast.LENGTH_LONG).show();
	}

	
	/*
	 * @author Rohan Balakrishnan
	 * @param intent Intent message sent from the activity. 
	 * @param flags 
	 * @param startId 
	 * @return void
	 * 
	 * \brief This method is called whenever the service is called explicitly by the activity to serve
	 * as a constructor for all the functionality of the following class.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		_startService(intent);
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.
		return START_STICKY;
	}

	/*
	 * @author Rohan Balakrishnan
	 * @param intent Intent message sent from the activity. 
	 * 
	 * @return void
	 * 
	 * \brief Take the phone's current location, perform a HTTP-get request to a database, and
	 * retrieve POIs within a given range of the user. Put said POIs into a List object, 
	 * and close the connection.
	 */
	private void _startService(Intent intent) {
		
		
		String context = Context.LOCATION_SERVICE;
		locationManager = (LocationManager) getSystemService(context);
		phone_bearing = -1; /*!<Current bearing of phone according to digital compass*/
		
		
		Criteria criteria = new Criteria(); /*!<Delineates specific parameters for GPS*/
		criteria.setAccuracy(Criteria.ACCURACY_FINE);
		criteria.setAltitudeRequired(false);
		criteria.setBearingRequired(true);
		criteria.setCostAllowed(true);
		criteria.setPowerRequirement(Criteria.POWER_LOW);
		String provider = locationManager.getBestProvider(criteria, true);
		//Obtain of the last known location
		location = locationManager.getLastKnownLocation(provider); 
		conn(location);
		locationManager.requestLocationUpdates(provider, 200, 1,
				locationListener);
		//Poll the Sensor manager to determine the last known heading of the phone
		sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		sm.registerListener(listener, sm
				.getDefaultSensor(Sensor.TYPE_ORIENTATION),
				SensorManager.SENSOR_DELAY_NORMAL);
		int sensorType = Sensor.TYPE_ORIENTATION;

		//Run a timer that will check which hotspots are in view every 200ms
		updatetimer = new Timer("UpdateTimer"); /*!<Timer that refreshes the current bearing when a change is detected*/
		updatetimer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				checkHotspots();
			}
		}, 1000, 200);
	}

	/*
	 * @author Rohan Balakrishnan
	 * \brief LocationListener object to keep track of when there is a change in user displacement detected
	 */
	private final LocationListener locationListener = new LocationListener() {
		
		/*
		 * @author Rohan Balakrishnan
		 * @param location Current location of the user
		 * \brief If a change to the GPS is detected, provide the conn method with the updated location
		 */
		
		
		public void onLocationChanged(Location location) {
			if(CurrentLocation == null)
			{
				CurrentLocation = location;
				LocationChanged= true;	
			}
			else
			{
				if ((CurrentLocation.getLatitude() == location.getLatitude()) && (CurrentLocation.getLongitude() == location.getLongitude()))
				{
					LocationChanged= false;
				}
				else
				{
					LocationChanged = true;
					CurrentLocation = location;
				}
			}
			if (LocationChanged)
			{
				conn(location);
				LocationChanged = false;
			}
		}

		public void onProviderDisabled(String provider) {

		}

		public void onProviderEnabled(String provider) {
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
		}
	};
	
	/*
	 * @author Rohan Balakrishnan
	 * \brief Sensor listener to update phone's current bearing whenever it detects a change in bearing. 
	 */
	private final SensorEventListener listener = new SensorEventListener() {

		@Override
		public void onSensorChanged(SensorEvent event) {

			setDirection(event.values[0]);
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}
	};
	
	/*
	 * @author Rohan Balakrishnan
	 * @param location Current location of the phone which is based on GPS readings
	 * 
	 * @return void
	 * 
	 * 
	 * \brief Take the phone's current location, perform a HTTP-get request to a database, and
	 * retrieve POIs within a given range of the user. Put said POIs into a List object, 
	 * and close the connection.
	 */
	public void conn(Location location) {
		if (location != null) {
			latitude = location.getLatitude();
			longitude = location.getLongitude();
			accuracy = location.getAccuracy();
			
		} else {
			latitude = 0;
			longitude = 0;
			accuracy = 0;
		}
		HttpClient httpclient = new DefaultHttpClient(); /*!<Used to establish a HTTP session*/
		HttpGet httpget = new HttpGet(
				"http://dioramang.ecs.umass.edu/layardotnet/test.layar?lon="
						+ longitude + "&radius=1000&lat=" + latitude
						+ "&layerName=ardiorama&accuracy=" + accuracy + ""); 

/*		HttpGet httpget = new HttpGet(
				"http://dioramang.ecs.umass.edu/layardotnet/test.layar?lon=-72.52901583909988"
						 + "&radius=5000&lat=42.39315198796079"
						+ "&layerName=ardiorama&accuracy=" + accuracy + "");
*/
		// Create a response handle
		ResponseHandler<String> responseHandler = new BasicResponseHandler();
		String responseBody = ""; /*!<Store response from server*/
		try {
			responseBody = httpclient.execute(httpget, responseHandler); 
			Gson gson = new Gson();
			Data obj = new Data();
			obj = gson.fromJson(responseBody, Data.class); /*!<Parse through JSON that is returned*/
			hotspots = obj.getHotspots(); 
		}catch(Exception e){
			Toast.makeText(ConnectorService.this, "Server is unreachable...", Toast.LENGTH_LONG);
			System.exit(-1);
		}

		//Close connection
		httpclient.getConnectionManager().shutdown();
	}
	/*
	 * @author Rohan Balakrishnan
	 * 
	 * @return void
	 * 
	 * \brief Used to determine which POIs are within field of view of the camera, and to communicate those POIs to the
	 * activity via an intent.   
	 */
	public void checkHotspots() {
		// Log.d("CHECKHOTSPOTS", "Checking hotspots...");
		String POIoutputtext = "***POI in view***\n";
		float dir = Direction; 
		phone_bearing = getDirection(); /*!<Current phone bearing*/
		boolean flag_check=false; /*!<Used to indicate that POI are in view*/
		boolean listiterationdone = false; /*!<Check whether all items that are in the hotspots List have been looked at*/
		int offset_from_center=0; /*!<Offset of the POI bearing from the phone bearing (positive means left, negative means right)*/
		double temp_poi; /*!<Temp variable for POI bearing*/
		
		if (phone_bearing != (float) -1) {
			Log.d("Hotspots Size",String.valueOf(hotspots.size()));
			int flag=0; /*!<Indicates that a POI needs to be transmitted to the activity via intent*/
			int in_view=0; /*!<Total number of POI in view*/
			float upper_bound, lower_bound;
			double lat1 = latitude; /*!<Latitude of the user*/
			double long1 = longitude; /*!<Longitude of the user*/
			double lat2; /*!<Latitude of the POI*/
			double long2; /*!<Longitude of the POI*/ 

			/*
			 * Calculate the bounds created by the FIELD_OF_VIEW from the 
			 * current bearing of the phone
			 */

			upper_bound = ((phone_bearing + (FIELD_OF_VIEW / 2)) % 360);
			lower_bound = ((phone_bearing - (FIELD_OF_VIEW / 2)));

			if (lower_bound < 0)
			{
				lower_bound = ((360 + phone_bearing - (FIELD_OF_VIEW / 2)) % 360);
			}
			if ((upper_bound >= 0) && (upper_bound < (FIELD_OF_VIEW)))
			{
				upper_bound = ((360 + phone_bearing + (FIELD_OF_VIEW / 2)));
			}
			if(phone_bearing==44){
				
			}
			/*
			 * Iterate over all values that were retrieved from the server
			 * 
			 */
			for (int i = 0; i < hotspots.size(); i++) {
				Data.POIFromObject p = hotspots.get(i);

				lat2 = p.getLat() / 1000000;
				long2 = p.getLon() / 1000000;

				
				double poi_bearing = 
					BearingDetermination.Bearing(lat1, long1,lat2, long2); /*!<Bearing of POI in current iteration of the for-loop*/
				/*	temp_poi=poi_bearing;
				if ((temp_poi >= 0) && (temp_poi < (FIELD_OF_VIEW)))
				{
					temp_poi = ((360 + temp_poi));
				}*/
				
				Log.d("Phone bearing", String.valueOf(phone_bearing));
				Log.d("POI Bearing",String.valueOf(poi_bearing));
				Log.d("BOUNDS",lower_bound+", "+upper_bound);
				
				/*
				 * If a POI offset from center is less than FIELD_OF_VIEW/2, then it is in view.
				 * Otherwise, ignore it. 
				 */

				//Create local versions of global phone and poi bearings
				float phone=phone_bearing; 
				double poi=poi_bearing;
				
				boolean ConditionA= false;
				boolean ConditionB= false;
				float TranslationAmount;
				float ThetaPOIB=0;
				float ThetaCompassB= 0;
				float DiffAngleB=0;
				float DiffAngleC=0;
				float DiffAngleD=0;
				float TempDifference=0;
				float FinalDifference=0;
				
				
				//Difference between angle of compass and a point
				float ThetaCompassA = phone;
				float ThetaPOIA = (float)poi;
				
				if (ThetaCompassA > 180)
				{
					
					// Compass Reading is more than 180			
					ThetaCompassB = phone - 360;
					ConditionA = true;
				}
				
				if (ThetaPOIA > 180)
				{
					// POI bearing is more than 180			
					ThetaPOIB = ThetaPOIA - 360;
					ConditionB = true;
				}
				
				
				float DiffAngleA = ThetaCompassA - ThetaPOIA;
				
				if (ConditionB)
				{
					DiffAngleB = ThetaCompassA - ThetaPOIB;
				}
				if (ConditionA)
				{
					DiffAngleC = ThetaCompassB - ThetaPOIA;
				}
				if (ConditionA && ConditionB)
				{
					DiffAngleD = ThetaCompassB - ThetaPOIB;
				}
				
				if (ConditionA &&  ConditionB)
				{
					float diff = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleB));
					TempDifference = Math.min(Math.abs(diff), Math.abs(DiffAngleD));
				}
				if (ConditionA)
					{
						TempDifference = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleC));
						
					}
				if (ConditionB)
					{
						TempDifference = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleB));
					}
				if (!ConditionA && !ConditionB)
				{
					TempDifference = Math.abs(DiffAngleA);
				}
				
				if (TempDifference == Math.abs(DiffAngleA))
				{
					FinalDifference = DiffAngleA;
				}
				else if (TempDifference == Math.abs(DiffAngleB))
				{
					FinalDifference = DiffAngleB;
				}
				else if (TempDifference == Math.abs(DiffAngleC))
				{
					FinalDifference = DiffAngleC;
				}
				else if (TempDifference == Math.abs(DiffAngleD))
				{
					FinalDifference = DiffAngleD;
					
				}
				offset_from_center=(int)FinalDifference;

				
				if (Math.abs(offset_from_center)<(FIELD_OF_VIEW/2)) 
				{
					in_view++;
					//offset_from_center = 0;
					//Log.d("Phone Bearing",String.valueOf(phone_bearing));		
					flag=1;
					flag_check = true;
					
					/* 
					 * Depending on the value of left_or_right determine 
					 * what the offset from the center of the screen is
					 * */
					
					/*float phone=phone_bearing;
					double poi=poi_bearing;
					
					boolean ConditionA= false;
					boolean ConditionB= false;
					float TranslationAmount;
					float ThetaPOIB=0;
					float ThetaCompassB= 0;
					float DiffAngleB=0;
					float DiffAngleC=0;
					float DiffAngleD=0;
					float TempDifference=0;
					float FinalDifference=0;
					
					
					//Difference between angle of compass and a point
					float ThetaCompassA = phone;
					float ThetaPOIA = (float)poi;
					
					if (ThetaCompassA > 180)
					{
						
						// Compass Reading is more than 180			
						ThetaCompassB = phone - 360;
						ConditionA = true;
					}
					
					if (ThetaPOIA > 180)
					{
						// POI bearing is more than 180			
						ThetaPOIB = ThetaPOIA - 360;
						ConditionB = true;
					}
					
					
					float DiffAngleA = ThetaCompassA - ThetaPOIA;
					
					if (ConditionB)
					{
						DiffAngleB = ThetaCompassA - ThetaPOIB;
					}
					if (ConditionA)
					{
						DiffAngleC = ThetaCompassB - ThetaPOIA;
					}
					if (ConditionA && ConditionB)
					{
						DiffAngleD = ThetaCompassB - ThetaPOIB;
					}
					
					if (ConditionA &&  ConditionB)
					{
						float diff = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleB));
						TempDifference = Math.min(Math.abs(diff), Math.abs(DiffAngleD));
					}
					if (ConditionA)
						{
							TempDifference = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleC));
							
						}
					if (ConditionB)
						{
							TempDifference = Math.min(Math.abs(DiffAngleA), Math.abs(DiffAngleB));
						}
					if (!ConditionA && !ConditionB)
					{
						TempDifference = Math.abs(DiffAngleA);
					}
					
					if (TempDifference == Math.abs(DiffAngleA))
					{
						FinalDifference = DiffAngleA;
					}
					else if (TempDifference == Math.abs(DiffAngleB))
					{
						FinalDifference = DiffAngleB;
					}
					else if (TempDifference == Math.abs(DiffAngleC))
					{
						FinalDifference = DiffAngleC;
					}
					else if (TempDifference == Math.abs(DiffAngleD))
					{
						FinalDifference = DiffAngleD;
						
					}
					offset_from_center=(int)FinalDifference;
					 */

					double poi_distance = (float) BearingDetermination
							.Distance(lat1, long1, lat2, long2); /*!<Calculate distance of POI from user*/
					
					//Set those values within POI object
					p.setBearing((float) poi); 
					p.setDistance(poi_distance);

				}//end of POI in view if-statement
				
				//Check whether current iteration if last iteration
				if(i==hotspots.size()-1)
				{
					Log.d("In-View",String.valueOf(in_view));
					listiterationdone = true;
					

					if (flag==0)
					{
						if (flag_check)
						{
							//call method to announce intent
							announceNewPOI(null,50, true,false);
						}
					}
					
				}//check if last iteration of for-loop
				
				//If data needs to be transmitted, do it here
				if(flag==1)
				{
					Log.d("Offset",p.getId()+", "+offset_from_center+", ["+poi_bearing+","+phone_bearing+"]");
					announceNewPOI(p, offset_from_center, listiterationdone,false);
					flag=0;
				}//if-statement to check if data needs to be transmitted
			}//end of for-loop
			
			//No POI in view
			if(!flag_check)
			{
				Log.d("POIinView",String.valueOf(hotspots.size()));
				announceNewPOI(null, 0,true,true);
			}
		}
	}

	/*
	 * @author Rohan Balakrishnan
	 * @param poi POI object that is to be dealt with (may be null object to indicate a dummy payload for intent broadcast)
	 * @param angle_offset Offset between phone bearing and POI bearing
	 * @param listiterationdone Iteration over hotspots is done or not
	 * @param empty Indicates whether POI are in view or not
	 * 
	 * @return void
	 * 
	 * \brief Transmit data received from checkHotspots method back to activity via intent
	 */
	private void announceNewPOI(Data.POIFromObject poi, int angle_offset,
		boolean listiterationdone,boolean empty) {
		Intent intent = new Intent(POI_IN_VIEW);
		
		//Check POI are in view
		if(!empty){
			if((poi!=null))
			{
				intent.putExtra("id", poi.getId());
				intent.putExtra("bearing", poi.getBearing());
				intent.putExtra("distance", poi.getDistance());
				intent.putExtra("lat", poi.getLat());
				intent.putExtra("lon", poi.getLon());
				intent.putExtra("type", poi.getType());
				intent.putExtra("angle_offset", angle_offset);
				Log.d("ANGLE_SERVICE",String.valueOf(angle_offset));
				intent.putExtra("phone_bearing", phone_bearing);
				intent.putExtra("listiterationdone", listiterationdone);
				intent.putExtra("empty_poi",false);
			}
			else{
				intent.putExtra("listiterationdone", listiterationdone);
				intent.putExtra("empty_poi",false);
			}
			sendBroadcast(intent);
			
		}
		else{
			intent.putExtra("empty_poi",true);
			intent.putExtra("listiterationdone", listiterationdone);
			sendBroadcast(intent);
		}
		
	}
	
	/*
	 * @author Rohan Balakrishnan
	 * @param direction Current phone bearing
	 * @return void
	 * 
	 * \brief Set the current bearing of the phone
	 */
	public void setDirection(float direction) {
		Direction = direction;
		Log.d("Direction", String.valueOf(Direction));
	}
	
	/*
	 * @author Rohan Balakrishnan
	 * 
	 * @return void
	 * 
	 * \brief Return the current direction of the phone, and cater to whether the phone is currently
	 * in landscape or portrait mode. 
	 */
	public static float getDirection() {
		float tempDirection = Direction;

		if (ChangeInModeToLandscape) {

			if (tempDirection > 269) {
				tempDirection = tempDirection - 360 + 90;
			} else {
				tempDirection = tempDirection + Deviation;
			}

		}
		return tempDirection;
	}
}